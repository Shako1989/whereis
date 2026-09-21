package az.technest.whereis.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import az.technest.whereis.assistant.AiAssistant;
import az.technest.whereis.plan.PlanLimitEnforcer;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts the IDOR-prevention convention into a build failure: owned aggregates must be
 * loaded through userId-scoped finders, never via bare findById. (auth is exempt: it loads
 * the user by an id derived from a validated refresh token, and the storage janitor/cleanup
 * work on the ownership-free deletion queue.)
 */
@AnalyzeClasses(packages = "az.technest.whereis", importOptions = ImportOption.DoNotIncludeTests.class)
class OwnershipScopingArchTest {

    @ArchTest
    static final ArchRule ownedAggregatesUseScopedFinders =
            noClasses()
                    .that().resideInAnyPackage(
                            "..whereis.space..", "..whereis.location..", "..whereis.item..",
                            "..whereis.search..", "..whereis.assistant..", "..whereis.storage..",
                            "..whereis.marketplace..")
                    .should().callMethod(CrudRepository.class, "findById", Object.class)
                    .because("owned resources must be fetched with userId-scoped repository methods");

    @ArchTest
    static final ArchRule controllersNeverTouchRepositories =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().areAssignableTo(CrudRepository.class)
                    .because("controllers go through services; ownership checks live there");

    /**
     * whereis-spec §6, "no AI call inside a DB transaction", as a build failure. Direct calls only:
     * it catches the obvious regression — a new caller of the port sitting right at a transaction
     * boundary, which is exactly what the assistant_messages bookkeeping made tempting.
     */
    @ArchTest
    static final ArchRule noTransactionalMethodCallsTheAiPort =
            noMethods()
                    .that().areAnnotatedWith(Transactional.class)
                    .should(callAMethodOn(AiAssistant.class))
                    .because("provider latency must never hold a database connection or a lock");

    /**
     * Same rule, same justification, for the Play Developer API: a Google round trip inside a
     * transaction holds a database connection (and any lock the transaction took) for as long as
     * Google feels like taking. {@code PurchaseVerificationService} has no {@code @Transactional}
     * anywhere for exactly this reason; every write of that flow is a separate
     * {@code SubscriptionWriter} method reached through the proxy.
     */
    @ArchTest
    static final ArchRule noTransactionalMethodCallsThePlayPort =
            noMethods()
                    .that().areAnnotatedWith(Transactional.class)
                    .should(callAMethodOn(PlaySubscriptionsApi.class))
                    .because("provider latency must never hold a database connection or a lock");

    /**
     * Entitlement has ONE answer, and it is computed in one place. Nothing outside {@code plan/}
     * may read subscription state — a second reader would eventually disagree with
     * {@code PlanLimitEnforcer#effectiveTierOf}, and then the screen and the wall say different
     * things. This also keeps the one deliberately UNSCOPED finder in the codebase
     * ({@code findByPurchaseToken}) unreachable from anywhere that does not know why it is unscoped.
     */
    @ArchTest
    static final ArchRule onlyThePlanPackageReadsSubscriptions =
            noClasses()
                    .that().resideOutsideOfPackages("..whereis.plan..")
                    .should().dependOnClassesThat().haveSimpleName("UserSubscriptionRepository")
                    .because("entitlement is decided once, in PlanLimitEnforcer");

    /**
     * <strong>"Nothing existing is ever taken away" as a build failure.</strong> Limits gate
     * CREATION and nothing else, and that "nothing else" cannot be expressed by a runtime
     * assertion — only by the absence of a call.
     *
     * <p>The rule is per METHOD, not per class, and that is the whole point: {@code ItemService}
     * legitimately calls {@code requireRoomForAnotherItem} from {@code createAt}, so a class-level
     * exemption would let {@code ItemService.update} — the unarchive path — quietly acquire a plan
     * check and start refusing to restore an item the user already owns. Mutation-checked exactly
     * that way.
     */
    @ArchTest
    static final ArchRule onlyTheThreeCreationMethodsConsultThePlan =
            noMethods()
                    .that(couldNotLegitimatelyRefuseACreation())
                    .should(callAMethodNamedStartingWith("requireRoom", PlanLimitEnforcer.class))
                    .because("limits refuse creation and nothing else; every other path must never refuse");

    /**
     * Everything except the THREE creation methods and {@code plan/} itself.
     *
     * <p>The rule matches by NAME PREFIX {@code requireRoom}, so a new guard inherits the
     * restriction automatically and its one legitimate call site has to be named here or the build
     * fails. That is the intended cost: it is what keeps "limits refuse creation and NOTHING else"
     * a build failure rather than a promise.
     */
    private static DescribedPredicate<JavaMethod> couldNotLegitimatelyRefuseACreation() {
        return new DescribedPredicate<>(
                "are not SpaceService.create, ItemService.createAt, ListingService.publish,"
                        + " or inside ..whereis.plan..") {
            @Override
            public boolean test(JavaMethod method) {
                String owner = method.getOwner().getFullName();
                if (owner.startsWith("az.technest.whereis.plan.")) {
                    return false;
                }
                boolean spaceCreation = "az.technest.whereis.space.SpaceService".equals(owner)
                        && "create".equals(method.getName());
                boolean itemCreation = "az.technest.whereis.item.ItemService".equals(owner)
                        && "createAt".equals(method.getName());
                boolean listingCreation =
                        "az.technest.whereis.marketplace.ListingService".equals(owner)
                                && "publish".equals(method.getName());
                return !spaceCreation && !itemCreation && !listingCreation;
            }
        };
    }

    private static ArchCondition<JavaMethod> callAMethodNamedStartingWith(String prefix, Class<?> owner) {
        return new ArchCondition<>("call a " + owner.getSimpleName() + " method named " + prefix + "*") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().isAssignableTo(owner))
                        .filter(call -> call.getTarget().getName().startsWith(prefix))
                        .forEach(call -> events.add(SimpleConditionEvent.satisfied(method, call.getDescription())));
            }
        };
    }

    private static ArchCondition<JavaMethod> callAMethodOn(Class<?> port) {
        return new ArchCondition<>("call a method on " + port.getSimpleName()) {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().isAssignableTo(port))
                        .forEach(call -> events.add(SimpleConditionEvent.satisfied(method, call.getDescription())));
            }
        };
    }
}
