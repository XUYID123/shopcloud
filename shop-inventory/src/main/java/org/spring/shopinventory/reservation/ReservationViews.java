package org.spring.shopinventory.reservation;

import java.time.LocalDate;
import java.util.List;

public final class ReservationViews {
    private ReservationViews() { }

    public record BatchAllocation(String skuId, String batchId, String batchNo, int quantity,
                                  LocalDate expiresOn, String qualityStatus) { }
    public record Reservation(String orderNo, String state, List<BatchAllocation> allocations, String failureCode) { }
    public record Allocation(String orderNo, String reservationState, boolean shippable,
                             List<BatchAllocation> allocations) { }
}
