package com.pickme.car.pickup.domain

import org.junit.Assert.*
import org.junit.Test

class OrderSchedulerTest {
    @Test fun fourPlannedMinutesWaitFourActualMinutes() {
        val plan = OrderScheduler.plan(travelMin = 13, prepMin = 9)
        assertEquals(4, plan.sendDelayMin)
        assertEquals(0, plan.waitAtStoreMin)
        assertEquals(240_000L, OrderScheduler.delayMillis(plan))
    }

    @Test fun longerPreparationSendsImmediatelyAndReportsWaitingAtStore() {
        val plan = OrderScheduler.plan(travelMin = 9, prepMin = 13)
        assertEquals(0L, OrderScheduler.delayMillis(plan))
        assertEquals(4, plan.waitAtStoreMin)
    }

    @Test fun equalTravelAndPreparationSendsImmediatelyWithoutWaiting() {
        val plan = OrderScheduler.plan(travelMin = 9, prepMin = 9)
        assertEquals(0L, OrderScheduler.delayMillis(plan))
        assertEquals(0, plan.waitAtStoreMin)
    }
}
