package az.technest.whereis.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import az.technest.whereis.assistant.AiAssistant;
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
                            "..whereis.search..", "..whereis.assistant..", "..whereis.storage..")
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
