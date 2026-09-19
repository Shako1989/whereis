package az.technest.whereis.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.PurchaseVerificationService;
import az.technest.whereis.plan.SubscriptionLinkResolver;
import az.technest.whereis.plan.reconcile.PlayCancellationJanitor;
import az.technest.whereis.plan.reconcile.SubscriptionReconciler;
import az.technest.whereis.plan.reconcile.VoidedPurchaseSweeper;
import az.technest.whereis.plan.rtdn.RtdnService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>The guardrail {@code OwnershipScopingArchTest#noTransactionalMethodCallsThePlayPort} does
 * NOT provide, stated as a test instead of as a false claim in a javadoc.</strong>
 *
 * <p>That ArchUnit rule is {@code noMethods().that().areAnnotatedWith(Transactional.class)}, and it
 * inspects each method's OWN annotations. A CLASS-level {@code @Transactional} on any of the
 * orchestrators below would hold a database connection across a Google round trip — exactly the
 * regression the rule is cited as preventing — and the build would stay green.
 *
 * <p>So the absence is asserted directly, for every class that talks to the Play port outside a
 * transaction by design. Mutation check to RUN rather than assume: put {@code @Transactional} on
 * {@link SubscriptionReconciler} and this test must fail (the ArchUnit rule will not).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NoTransactionAroundThePlayPortTest {

    static List<Class<?>> orchestrators() {
        return List.of(PurchaseVerificationService.class, RtdnService.class,
                SubscriptionReconciler.class, VoidedPurchaseSweeper.class,
                PlayCancellationJanitor.class, SubscriptionLinkResolver.class);
    }

    @ParameterizedTest
    @MethodSource("orchestrators")
    void carriesNoTransactionalAtClassLevel(Class<?> orchestrator) {
        assertThat(orchestrator.getAnnotation(Transactional.class))
                .as("%s calls the Play port (or a writer that does) between transactions; a class-level "
                        + "@Transactional would hold a connection across a Google round trip and the "
                        + "ArchUnit rule, which reads per-method annotations, would not notice",
                        orchestrator.getSimpleName())
                .isNull();
    }

    @ParameterizedTest
    @MethodSource("orchestrators")
    void carriesNoTransactionalOnAnyMethodEither(Class<?> orchestrator) {
        List<String> transactional = Arrays.stream(orchestrator.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Transactional.class))
                .map(Method::getName)
                .toList();

        assertThat(transactional)
                .as("%s must reach the database only through a writer bean", orchestrator.getSimpleName())
                .isEmpty();
    }
}
