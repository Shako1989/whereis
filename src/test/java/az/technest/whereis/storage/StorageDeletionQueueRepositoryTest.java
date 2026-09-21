package az.technest.whereis.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * The bulk enqueues, pinned by reading their own {@code @Query} text.
 *
 * <p><strong>Why reflection rather than a round trip.</strong> Since V14 there are two buckets, and
 * these two statements differ ONLY in which bucket column they select. Getting that wrong is the
 * quietest failure in the storage layer: the janitor is handed a correct object key against the
 * wrong bucket, deletes nothing, reports success, drops the queue row — and a published photo stays
 * on the open internet permanently, with nothing left in the database to find it by. No count, no
 * exception and no log line says so, which is exactly the class of mistake worth a cheap guard.
 */
class StorageDeletionQueueRepositoryTest {

    @Test
    void theAccountDeletionEnqueueOfPRIVATEObjectsUsesThePrivateBucketColumn() {
        String sql = queryOf("enqueueAllFilesOfUser");

        assertThat(sql).contains("f.bucket").contains("f.object_key");
        assertThat(sql).doesNotContain("published");
    }

    @Test
    void theAccountDeletionEnqueueOfPUBLISHEDCopiesUsesThePublishedBucketColumn() {
        String sql = queryOf("enqueuePublishedCopiesOfUser");

        assertThat(sql)
                .as("the published copy is in the world-readable bucket, not the private one")
                .contains("f.published_bucket")
                .contains("f.published_object_key");
        // The fragment "f.bucket," (the select-list form) must not appear at all.
        assertThat(sql.replace("f.published_bucket", "")).doesNotContain("f.bucket");
    }

    @Test
    void bothEnqueuesSupplyTheColumnsThatNoPrePersistCallbackWillFillIn() {
        // A bulk INSERT bypasses StorageDeletionQueueEntry's @PrePersist, and all four of these
        // columns are NOT NULL.
        for (String method : new String[]{"enqueueAllFilesOfUser", "enqueuePublishedCopiesOfUser"}) {
            assertThat(queryOf(method)).as(method)
                    .contains("attempts")
                    .contains("next_attempt_at")
                    .contains("created_at")
                    .contains("gen_random_uuid()");
        }
    }

    private static String queryOf(String methodName) {
        for (Method method : StorageDeletionQueueRepository.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Query query = method.getAnnotation(Query.class);
                assertThat(query).as("@Query on " + methodName).isNotNull();
                return query.value();
            }
        }
        throw new AssertionError("no method named " + methodName);
    }
}
