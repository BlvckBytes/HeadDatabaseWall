package me.blvckbytes.head_database_wall;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

public class HeadDatabaseWallPlugin extends JavaPlugin {

  private static final long DISTANCE_REMOVAL_CHECK_PERIOD_T = 40;
  private HeadWallSessionRegistry sessionRegistry;

  @Override
  public void onEnable() {
    var logger = getLogger();

    try {
      if (!Bukkit.getServer().getPluginManager().isPluginEnabled("HeadDatabase"))
        throw new IllegalStateException("Expected the plugin \"HeadDatabase\" to be loaded.");

      var headDatabase = new HeadDatabaseAPI();

      sessionRegistry = new HeadWallSessionRegistry();

      Bukkit.getServer().getPluginManager().registerEvents(sessionRegistry, this);

      Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(
        this, sessionRegistry::checkSessionsForDistanceRemoval,
        0L, DISTANCE_REMOVAL_CHECK_PERIOD_T
      );

      var commandHandler = new HeadDatabaseWallCommand(headDatabase, sessionRegistry);

      Objects.requireNonNull(getCommand("headdatabasewall")).setExecutor(commandHandler);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "An error occurred while trying to setup the plugin; shutting down", e);
    }
  }

  @Override
  public void onDisable() {
    if (sessionRegistry != null)
      sessionRegistry.onShutdown();
  }
}