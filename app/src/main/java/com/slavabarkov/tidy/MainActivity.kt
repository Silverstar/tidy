/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy

import android.os.Bundle
import android.util.Log // Added Log import
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp // Keep this import
import androidx.navigation.ui.setupActionBarWithNavController


class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController

        // Define AppBarConfiguration with ONLY the start destination
        // This tells setupActionBarWithNavController which destination is top-level
        appBarConfiguration = AppBarConfiguration(setOf(R.id.indexFragment))

        // Initial setup - Links NavController to ActionBar
        setupActionBarWithNavController(navController, appBarConfiguration)

        // --- START: Added Destination Change Listener ---
        // Add a listener to respond to navigation events
        navController.addOnDestinationChangedListener { controller, destination, arguments ->
            Log.d("MainActivity", "Listener: Navigated to destination: ${destination.label} (ID: ${destination.id})")
            // This listener helps confirm navigation events occur.
            // The setupActionBarWithNavController should handle UI updates like the Up arrow visibility.
        }
        // --- END: Added Destination Change Listener ---
    }

    // --- START: Modified onSupportNavigateUp ---
    /**
     * Handles navigation when the Up button (<-) in the ActionBar is pressed.
     * Reverted to using navigateUp(appBarConfiguration) as popBackStack() failed unexpectedly.
     */
    override fun onSupportNavigateUp(): Boolean {
        Log.d("MainActivity", "onSupportNavigateUp called. Current Destination: ${navController.currentDestination?.label}")

        // Revert back to using navigateUp with AppBarConfiguration
        val navigatedUp = navController.navigateUp(appBarConfiguration)
        Log.d("MainActivity", "navController.navigateUp(appBarConfiguration) returned: $navigatedUp")

        // If NavController handled it, return true. Otherwise, fall back to default behavior.
        return navigatedUp || super.onSupportNavigateUp()

        // --- Previous attempt using popBackStack() ---
        // val popped = navController.popBackStack()
        // Log.d("MainActivity", "navController.popBackStack() returned: $popped")
        // return popped || super.onSupportNavigateUp()
        // --- End Previous attempt ---
    }
    // --- END: Modified onSupportNavigateUp ---
}