package com.braava.local

import android.app.Application
import com.braava.local.data.RobotRepository

class BraavaApp : Application() {

    lateinit var repository: RobotRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = RobotRepository(this)
    }
}
