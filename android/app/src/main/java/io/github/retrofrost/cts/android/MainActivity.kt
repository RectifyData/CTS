package io.github.retrofrost.cts.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.retrofrost.cts.android.rewrite.EditorSession
import io.github.retrofrost.cts.android.rewrite.ProjectStore
import io.github.retrofrost.cts.android.rewrite.RewriteEditor
import io.github.retrofrost.cts.android.ui.theme.CtsTheme

class MainActivity : ComponentActivity() {
    private lateinit var session: EditorSession
    private lateinit var store: ProjectStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = ProjectStore(this)
        session = EditorSession(store.restore())
        setContent {
            CtsTheme(dynamicColor = true) { RewriteEditor(session, store) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::session.isInitialized) {
            store.save(session.project, session.revision)
        }
    }
}
