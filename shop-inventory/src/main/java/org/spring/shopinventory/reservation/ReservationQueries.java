package org.spring.shopinventory.reservation;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

@Service
class ReservationQueries {
    private final ReservationStore store;
    private final TransactionTemplate reads;

    ReservationQueries(ReservationStore store, PlatformTransactionManager manager) {
        this.store = store;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    ReservationViews.Reservation find(String orderNo) {
        if (!TradeEvents.validOrderNo(orderNo)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        try {
            return reads.execute(status -> {
                var reservation = store.find(orderNo);
                if (reservation == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                return new ReservationViews.Reservation(orderNo, reservation.state(), store.allocations(orderNo),
                        reservation.failureCode());
            });
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | RecoverableDataAccessException | CannotCreateTransactionException failure) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    ReservationViews.Allocation allocation(String orderNo) {
        var reservation = find(orderNo);
        var today = LocalDate.now(ReservationCommands.BUSINESS_ZONE);
        boolean shippable = "CONFIRMED".equals(reservation.state()) && !reservation.allocations().isEmpty()
                && reservation.allocations().stream().allMatch(batch ->
                "SELLABLE".equals(batch.qualityStatus()) && batch.expiresOn().isAfter(today));
        return new ReservationViews.Allocation(orderNo, reservation.state(), shippable, reservation.allocations());
    }
}
