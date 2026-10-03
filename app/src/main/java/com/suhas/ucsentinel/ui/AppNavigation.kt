@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.suhas.globaledgeai.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class MainTab(val label:String,val icon:ImageVector){
    UC("UC",Icons.Default.TrendingUp),
    STRATEGIES("Strategies",Icons.Default.ShowChart),
    MULTIFY("Multify",Icons.Default.NotificationsActive),
    GLOBAL("Global",Icons.Default.Public),
    PORTFOLIO("Portfolio",Icons.Default.AccountBalanceWallet)
}

@Composable
fun AppNavigation(vm:MainViewModel){
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember{mutableStateOf(MainTab.UC)}
    var utilityOpen by remember{mutableStateOf(false)}
    var utilityPage by remember{mutableStateOf("auth")}

    Scaffold(
        containerColor=MaterialTheme.colorScheme.background,
        topBar={
            TopAppBar(
                title={Text(if(utilityOpen)"Setup" else "Global Edge")},
                navigationIcon={if(utilityOpen)IconButton(onClick={utilityOpen=false}){Icon(Icons.Default.ArrowBack,"Back")}},
                actions={
                    if(!utilityOpen)IconButton(onClick={utilityOpen=true}){Icon(Icons.Default.Settings,"Authentication and settings")}
                }
            )
        },
        bottomBar={
            if(!utilityOpen)NavigationBar{
                MainTab.entries.forEach{item->
                    NavigationBarItem(
                        selected=tab==item,
                        onClick={tab=item},
                        icon={Icon(item.icon,item.label)},
                        label={Text(item.label)}
                    )
                }
            }
        }
    ){padding->
        if(utilityOpen){
            MoreScreen(state,vm,padding,utilityPage){utilityPage=it}
        }else when(tab){
            MainTab.UC->UpperCircuitCompactScreen(state,vm,padding)
            MainTab.STRATEGIES->StrategiesCompactScreen(state,vm,padding)
            MainTab.MULTIFY->MultifyCompactScreen(state,vm,padding)
            MainTab.GLOBAL->GlobalCompactScreen(state,vm,padding)
            MainTab.PORTFOLIO->PortfolioCompactScreen(state,vm,padding)
        }
    }
}
