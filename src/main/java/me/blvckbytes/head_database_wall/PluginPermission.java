package me.blvckbytes.head_database_wall;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.permissions.Permissible;

public enum PluginPermission {
  USE("use")
  ;

  private final String node;

  PluginPermission(String node) {
    this.node = "headdatabasewall." + node;
  }

  public boolean hasPermission(Permissible sender) {
    if (sender instanceof ConsoleCommandSender)
      return true;

    return sender.hasPermission(this.node);
  }
}
