package me.blvckbytes.head_database_wall;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import me.arcaniax.hdb.enums.CategoryEnum;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class HeadDatabaseWallCommand implements CommandExecutor, TabCompleter {

  private static final List<String> categoryNames = Arrays.stream(CategoryEnum.values()).map(Enum::name).toList();

  private final HeadDatabaseAPI headDatabase;
  private final HeadWallSessionRegistry sessionRegistry;

  public HeadDatabaseWallCommand(HeadDatabaseAPI headDatabase, HeadWallSessionRegistry sessionRegistry) {
    this.headDatabase = headDatabase;
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§cThis command is only available to players");
      return true;
    }

    if (!PluginPermission.USE.hasPermission(sender)) {
      player.sendMessage("§cYou have no permission to use this command.");
      return true;
    }

    if (args.length != 1) {
      player.sendMessage("§cUsage: /" + label + " <" + String.join(", ", categoryNames) + ">");
      return true;
    }

    CategoryEnum targetCategory;

    try {
      targetCategory = CategoryEnum.valueOf(args[0].toUpperCase());
    } catch (Exception e) {
      sender.sendMessage("§cUnknown category: " + args[0]);
      return true;
    }

    var targetHeads = headDatabase.getHeads(targetCategory);

    if (targetHeads.isEmpty()) {
      sender.sendMessage("§cThe category " + targetCategory + " does not hold any heads.");
      return true;
    }

    var session = sessionRegistry.createAndRegister(player, targetHeads);

    if (session == null) {
      player.sendMessage("§cYou're already in an active head-wall session!");
      return true;
    }

    session.show();

    sender.sendMessage("§aYour selected category holds " + targetHeads.size() + " heads.");
    sender.sendMessage("§aShowing page 1/" + session.getNumberOfPages());
    sender.sendMessage("§aLeft-click to request head, right-click to print infos");
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
    if (!PluginPermission.USE.hasPermission(sender))
      return List.of();

    if (args.length == 1)
      return categoryNames;

    return List.of();
  }
}
