package com.pickme.car.core.navi

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import org.junit.Assert.*
import org.junit.Test

class FriendWaypointTest {
    @Test fun pickupAlwaysUsesFriendSlotWithoutInventingPoi() {
        val result=friendWaypoint(37.55715,127.04375,"친구 탑승 장소")
        assertEquals(WaypointIndex.SECOND,result.waypointIndex)
        assertEquals(37.55715,result.latitude,0.0)
        assertEquals(127.04375,result.longitude,0.0)
        assertTrue(result.poiId.isNullOrEmpty())
    }
    private fun destination(lat:Double,id:String) = DestinationInfo(latitude=lat,longitude=127.1,displayLatitude=null,displayLongitude=null,name="목적지",address="시연",remainDistance=0,remainTime=0,tollFare=0,poiId=id,poiSubId="",poiProvider="test")
    @Test fun destinationUsesStablePoiIdentityInsteadOfSnappedEntrance() {
        assertTrue(sameNavigationDestination(destination(37.4,"same"),destination(37.4001,"same")))
        assertFalse(sameNavigationDestination(destination(37.4,"old"),destination(37.4,"new")))
        assertFalse(sameNavigationDestination(destination(37.4,""),destination(37.4001,"")))
    }
    private fun reported(lat:Double,lng:Double,name:String) = WaypointInfo(name=name,address="",latitude=lat,longitude=lng,displayLatitude=null,displayLongitude=null,remainDistance=0,remainTime=0,poiId="",poiSubId="",poiProvider="")
    @Test fun zeroCoordinatesConfirmOnlyTheNameNeverTheRequestedCoordinates() {
        assertEquals(ReportedWaypointMatch.NAME_ONLY,reportedWaypointMatch(reported(0.0,0.0,"픽업"),37.4,127.1,"픽업"))
        assertEquals(ReportedWaypointMatch.NONE,reportedWaypointMatch(reported(0.0,0.0,"다른 장소"),37.4,127.1,"픽업"))
        assertEquals(ReportedWaypointMatch.NONE,reportedWaypointMatch(reported(37.5,127.1,"픽업"),37.4,127.1,"픽업"))
        assertEquals(ReportedWaypointMatch.COORDINATE,reportedWaypointMatch(reported(37.4,127.1,"픽업"),37.4,127.1,"픽업"))
    }
    @Test fun invalidLocationCannotReachNavigation() {
        for(pair in listOf(Double.NaN to 127.0,37.0 to Double.POSITIVE_INFINITY,91.0 to 127.0,0.0 to 0.0)) {
            try {friendWaypoint(pair.first,pair.second,"장소");fail("invalid coordinate accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
