package az.technest.whereis.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.CrudRepository;

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
}
