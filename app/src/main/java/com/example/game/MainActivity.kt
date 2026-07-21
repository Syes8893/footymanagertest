package com.example.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// --- 1. DATA CLASSES (Matching FastAPI Models) ---
data class GameCreateRequest(val game_id: String)
data class JoinGameRequest(val game_id: String, val user_id: String, val team_id: String)

data class AvailableTeamsResponse(val available_teams: List<TeamData>)
data class TeamData(val id: String, val name: String, val league: String)

data class DashboardResponse(
    val season: Int,
    val week: Int,
    val is_transfer_window: Boolean,
    val team_id: String,
    val team_name: String,
    val league_name: String,
    val league_position: Any,
    val budget: Double,
    val prestige: Int,
    val weekly_wage_bill: Double,
    val squad_size: Int,
    val unread_inbox: Int
)

data class SimulateResponse(
    val message: String,
    val season: Int,
    val week: Int
)

// --- 2. API INTERFACE ---
interface FootballApi {
    @POST("api/game/create")
    suspend fun createGame(@Body req: GameCreateRequest): Any

    @GET("api/game/{game_id}/available_teams")
    suspend fun getAvailableTeams(@Path("game_id") gameId: String): AvailableTeamsResponse

    @POST("api/game/join")
    suspend fun joinGame(@Body req: JoinGameRequest): Any

    @GET("api/game/{game_id}/team/{user_id}/dashboard")
    suspend fun getDashboard(
        @Path("game_id") gameId: String,
        @Path("user_id") userId: String
    ): DashboardResponse

    @POST("api/game/{game_id}/simulate")
    suspend fun simulateWeek(@Path("game_id") gameId: String): SimulateResponse
}

object RetrofitClient {
    // IMPORTANT: REPLACE WITH YOUR ACTUAL SERVER IP OR URL BEFORE BUILDING
    // Example: "http://192.168.1.100:8000/"
    private const val BASE_URL = "http://localhost:8000/" 
    
    val instance: FootballApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FootballApi::class.java)
    }
}

// --- 3. VIEWMODEL ---
class FootballViewModel : ViewModel() {
    var dashboardState = mutableStateOf<DashboardResponse?>(null)
        private set
    var errorMessage = mutableStateOf<String?>(null)
        private set
    var isSimulating = mutableStateOf(false)
        private set

    // Hardcoded session details for demo purposes
    private val gameId = "mobile_session_1"
    private val userId = "player_1"

    fun initializeGame() {
        viewModelScope.launch {
            try {
                // 1. Try to create a new game
                try { 
                    RetrofitClient.instance.createGame(GameCreateRequest(gameId)) 
                } catch (e: Exception) { /* Ignore: Game might already exist */ }
                
                // 2. Fetch available teams and join the very first one
                val teamsResp = RetrofitClient.instance.getAvailableTeams(gameId)
                if (teamsResp.available_teams.isNotEmpty()) {
                    val firstTeamId = teamsResp.available_teams[0].id
                    try {
                        RetrofitClient.instance.joinGame(JoinGameRequest(gameId, userId, firstTeamId))
                    } catch (e: Exception) { /* Ignore: Might already be joined */ }
                }
                
                // 3. Fetch the dashboard data
                fetchDashboard()
            } catch (e: Exception) {
                errorMessage.value = "Init failed. Make sure your server is running and the IP is correct."
            }
        }
    }

    private suspend fun fetchDashboard() {
        try {
            dashboardState.value = RetrofitClient.instance.getDashboard(gameId, userId)
            errorMessage.value = null
        } catch (e: Exception) {
            errorMessage.value = "Failed to load dashboard: ${e.message}"
        }
    }

    fun simulateNextWeek() {
        viewModelScope.launch {
            isSimulating.value = true
            try {
                RetrofitClient.instance.simulateWeek(gameId)
                fetchDashboard() // Refresh data after advancing the week
            } catch (e: Exception) {
                errorMessage.value = "Simulation failed: ${e.message}"
            } finally {
                isSimulating.value = false
            }
        }
    }
}

// --- 4. UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FootballScreen() }
    }
}

@Composable
fun FootballScreen(viewModel: FootballViewModel = viewModel()) {
    val state = viewModel.dashboardState.value
    val error = viewModel.errorMessage.value
    val isSim = viewModel.isSimulating.value

    LaunchedEffect(Unit) { viewModel.initializeGame() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (error != null) {
            Text(text = "Error: $error", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.initializeGame() }) { Text("Retry Connection") }
        } else if (state == null) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Connecting to Game Server...")
        } else {
            Text(text = state.team_name, style = MaterialTheme.typography.headlineLarge)
            Text(text = state.league_name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Season: ${state.season} | Week: ${state.week}", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "League Position: ${state.league_position}")
                    Text(text = "Budget: $${String.format("%,.0f", state.budget)}")
                    Text(text = "Weekly Wages: $${String.format("%,.0f", state.weekly_wage_bill)}")
                    Text(text = "Squad Size: ${state.squad_size} Players")
                    
                    if (state.is_transfer_window) {
                        Text(text = "Transfer Window: OPEN", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(text = "Transfer Window: CLOSED")
                    }
                    
                    if (state.unread_inbox > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Unread Messages: ${state.unread_inbox}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { viewModel.simulateNextWeek() },
                enabled = !isSim
            ) {
                if (isSim) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Simulate Next Week")
                }
            }
        }
    }
}
