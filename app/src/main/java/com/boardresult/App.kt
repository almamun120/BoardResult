package com.boardresult

import android.app.Application
import com.boardresult.core.Graph
import com.boardresult.core.Prefs
import com.boardresult.data.AppDatabase
import com.boardresult.data.Seed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.app = applicationContext
        Graph.prefs = Prefs(this)
        Graph.db = AppDatabase.build(this)
        if (!Graph.prefs.seeded) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Seed.run(Graph.db)
                    Graph.prefs.seeded = true
                } catch (_: Exception) {
                }
            }
        }
    }
}
