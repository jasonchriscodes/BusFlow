package com.jason.publisher.main.realtime

object LiveBusStore {

    private val buses = mutableMapOf<String, LiveBusStatus>()

    fun update(bus : LiveBusStatus){
        buses[bus.aid] = bus
    }

    fun clear(){
        buses.clear()
    }

    fun activeOthers(
        selfAid : String,
        ttlMs : Long = 10_000,
    ) : List<LiveBusStatus>{
        val now = System.currentTimeMillis()
        return buses.values.filter {
            it.aid != selfAid && now - it.lastSeen <= ttlMs
        }
    }


}