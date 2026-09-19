package az.technest.whereis.plan.play;

import java.util.List;

/**
 * One page of {@code purchases.voidedpurchases.list}.
 *
 * @param purchases     the voids in this page; never null, may be empty
 * @param nextPageToken {@code tokenPagination.nextPageToken}, or null on the last page
 */
public record PlayVoidedPage(List<PlayVoidedPurchase> purchases, String nextPageToken) {

    public PlayVoidedPage {
        purchases = purchases == null ? List.of() : List.copyOf(purchases);
    }
}
