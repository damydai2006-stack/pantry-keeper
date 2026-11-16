package com.damy.pantry

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.damy.pantry.data.HistoryViewModel
import com.damy.pantry.data.ItemFormViewModel
import com.damy.pantry.data.ListViewModel
import com.damy.pantry.data.LoginViewModel
import com.damy.pantry.data.PantryRepository
import com.damy.pantry.data.shanghaiToday
import com.damy.pantry.ui.FamilyCreateScreen
import com.damy.pantry.ui.FamilyJoinScreen
import com.damy.pantry.ui.FamilyChooseScreen
import com.damy.pantry.ui.HomeScreen
import com.damy.pantry.ui.ItemFormScreen
import com.damy.pantry.ui.LoginScreen
import com.damy.pantry.ui.MembersScreen
import com.damy.pantry.ui.NicknameScreen
import com.damy.pantry.ui.PantryTheme
import com.damy.pantry.ui.StubScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as PantryApp).repo
        val connectivity = getSystemService(ConnectivityManager::class.java)
        repo.setOnline(connectivityOnline(connectivity))
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                repo.setOnline(connectivityOnline(connectivity))
            }

            override fun onLost(network: Network) {
                repo.setOnline(connectivityOnline(connectivity))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                repo.setOnline(connectivityOnline(connectivity))
            }
        })
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                repo.startForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                repo.stopForeground()
            }
        })
        setContent {
            PantryTheme {
                PantryRoot(
                    repo,
                    Modifier.statusBarsPadding().navigationBarsPadding().semantics { testTagsAsResourceId = true },
                )
            }
        }
    }
}

@Composable
private fun PantryRoot(repo: PantryRepository, modifier: Modifier) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "boot", modifier = modifier) {
        composable("boot") {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                try {
                    repo.boot()
                } catch (_: Exception) {
                    repo.setOnline(false)
                    if (repo.notice.value == null) repo.notice.value = "暂时连不上服务器"
                }
                val dest = when {
                    repo.session.token == null -> "login"
                    repo.family.value == null -> "family"
                    else -> "home"
                }
                nav.navigate(dest) { popUpTo("boot") { inclusive = true } }
            }
        }
        composable("login") {
            val vm: LoginViewModel = viewModel(factory = repo.factory { LoginViewModel(repo) })
            LoginScreen(vm, onFamily = {
                nav.navigate(if (needsDisplayName(repo)) "nickname/family" else "family") { popUpTo("login") { inclusive = true } }
            }, onHome = {
                nav.navigate(if (needsDisplayName(repo)) "nickname/home" else "home") { popUpTo("login") { inclusive = true } }
            })
        }
        composable("nickname/{next}") { entry ->
            val next = entry.arguments?.getString("next").orEmpty()
            NicknameScreen(repo, onBack = { nav.popBackStack() }, onSaved = {
                when (next) {
                    "home" -> nav.navigate("home") { popUpTo("nickname/$next") { inclusive = true } }
                    "family" -> nav.navigate("family") { popUpTo("nickname/$next") { inclusive = true } }
                    else -> nav.popBackStack()
                }
            })
        }
        composable("family") {
            val scope = rememberCoroutineScope()
            FamilyChooseScreen(
                onCreate = { nav.navigate("create") },
                onJoin = { nav.navigate("join") },
                onLogout = {
                    scope.launch {
                        repo.logout()
                        nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                },
            )
        }
        composable("create") {
            FamilyCreateScreen(repo, onBack = { nav.popBackStack() }, onDone = {
                nav.navigate("home") { popUpTo("family") { inclusive = true } }
            })
        }
        composable("join") {
            FamilyJoinScreen(repo, onBack = { nav.popBackStack() }, onDone = {
                nav.navigate("home") { popUpTo("family") { inclusive = true } }
            })
        }
        composable("home") {
            val listVm: ListViewModel = viewModel(factory = repo.factory { ListViewModel(repo) })
            val historyVm: HistoryViewModel = viewModel(factory = repo.factory { HistoryViewModel(repo) })
            HomeScreen(
                repo = repo,
                listVm = listVm,
                historyVm = historyVm,
                today = shanghaiToday(),
                onAddManual = { nav.navigate("form") },
                onEdit = { nav.navigate("edit/$it") },
                onStub = { nav.navigate("stub/$it") },
                onCapture = { nav.navigate("capture/$it") },
                onMembers = { nav.navigate("members") },
                onNickname = { nav.navigate("nickname/stay") },
                onLogout = { nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true } } },
                onRemoved = { nav.navigate("family") { popUpTo("home") { inclusive = true } } },
            )
        }
        composable("shelf") {
            com.damy.pantry.capture.ShelfPickScreen(shanghaiToday(), onBack = { nav.popBackStack() }, onDone = { prefill ->
                com.damy.pantry.capture.PendingCapture.prefill = prefill
                nav.navigate("form") { popUpTo("home") }
            })
        }
        composable("form") {
            val vm: ItemFormViewModel = viewModel(factory = repo.factory { ItemFormViewModel(repo) { shanghaiToday() } })
            val scope = rememberCoroutineScope()
            ItemFormScreen(vm, repo, null, onBack = { nav.popBackStack() }, onSaved = {
                val jpeg = com.damy.pantry.capture.PendingCapture.jpeg
                val created = vm.lastCreatedId
                if (jpeg != null && created != null) {
                    scope.launch {
                        runCatching { repo.uploadPhoto(created, jpeg) }
                        com.damy.pantry.capture.PendingCapture.jpeg = null
                    }
                }
                nav.popBackStack()
            })
        }
        composable("edit/{id}") { entry ->
            val id = entry.arguments?.getString("id")
            val vm: ItemFormViewModel = viewModel(key = id, factory = repo.factory { ItemFormViewModel(repo) { shanghaiToday() } })
            ItemFormScreen(vm, repo, id, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable("members") {
            MembersScreen(repo, onBack = { nav.popBackStack() }, onLeft = {
                nav.navigate("family") { popUpTo("home") { inclusive = true } }
            })
        }
        composable("capture/{mode}") { entry ->
            val mode = if (entry.arguments?.getString("mode") == "scan") com.damy.pantry.capture.CaptureMode.SCAN else com.damy.pantry.capture.CaptureMode.PHOTO
            com.damy.pantry.capture.CaptureEntry(
                mode = mode,
                lookup = { repo.barcode(it) },
                onDone = { prefill ->
                    com.damy.pantry.capture.PendingCapture.prefill = prefill
                    nav.navigate("form") { popUpTo("home") }
                },
                onManual = { prefill ->
                    com.damy.pantry.capture.PendingCapture.prefill = prefill
                    val route = if (prefill.manualMode == "shelf") "shelf" else "form"
                    nav.navigate(route) { popUpTo("home") }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("stub/{name}") { entry ->
            StubScreen(entry.arguments?.getString("name").orEmpty()) { nav.popBackStack() }
        }
    }
}

private fun needsDisplayName(repo: PantryRepository): Boolean {
    val name = repo.session.displayName
    return name.isNullOrBlank() || name == "用户"
}

private fun <T : ViewModel> PantryRepository.factory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <M : ViewModel> create(modelClass: Class<M>): M = create() as M
    }
