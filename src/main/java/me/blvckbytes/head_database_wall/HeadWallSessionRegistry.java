package me.blvckbytes.head_database_wall;

import me.arcaniax.hdb.object.head.Head;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class HeadWallSessionRegistry implements Listener {

  private static final double REMOVAL_DISTANCE_BLOCKS = 10;
  private static final double REMOVAL_DISTANCE_BLOCKS_SQUARED = REMOVAL_DISTANCE_BLOCKS * REMOVAL_DISTANCE_BLOCKS;

  private static final HeadWallParameters WALL_PARAMETER = new HeadWallParameters(5, 8, 3, Material.COAL_BLOCK);

  private final Map<UUID, HeadWallSession> sessionByPlayerId;

  public HeadWallSessionRegistry() {
    this.sessionByPlayerId = new HashMap<>();
  }

  public void checkSessionsForDistanceRemoval() {
    for (var sessionIterator = sessionByPlayerId.values().iterator(); sessionIterator.hasNext();) {
      var session = sessionIterator.next();
      var sessionDistanceSquared = session.distanceSquaredTo(session.viewer.getLocation());

      if (sessionDistanceSquared <= REMOVAL_DISTANCE_BLOCKS_SQUARED)
        continue;

      session.viewer.sendMessage("§cYou've exceeded the max distance of " + REMOVAL_DISTANCE_BLOCKS + "; exited session.");
      session.close();
      sessionIterator.remove();
    }
  }

  public @Nullable HeadWallSession createAndRegister(Player player, List<Head> heads) {
    var playerId = player.getUniqueId();

    if (sessionByPlayerId.containsKey(playerId))
      return null;

    var session = new HeadWallSession(player, heads, WALL_PARAMETER);

    this.sessionByPlayerId.put(playerId, session);
    return session;
  }

  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    var player = event.getPlayer();

    tryAccessSession(player, session -> {
      // TODO: Fake-blocks can de-spawn easily once clicked; this requires a lot of extra thought...
      event.setCancelled(true);

      var block = event.getClickedBlock();

      if (block == null)
        return;

      var correspondingHead = session.getHeadAtLocation(block.getLocation());

      if (correspondingHead == null) {
        player.sendMessage("§cPlease click directly on a head; left-click to request, right-click for information, sneak to exit");
        return;
      }

      var action = event.getAction();
      var isLeft = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;

      if (isLeft) {
        player.getInventory().addItem(correspondingHead.getHead());
        player.sendMessage("§aYou've been given the head " + correspondingHead.name);
        return;
      }

      player.sendMessage("§8§m                              ");
      player.sendMessage("§aName: " + correspondingHead.name);
      player.sendMessage("§aCategory: " + correspondingHead.c.name());
      player.sendMessage("§aTags: " + String.join(", ", correspondingHead.tags));
      player.sendMessage("§8§m                              ");
    });
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    sessionByPlayerId.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onSneak(PlayerToggleSneakEvent event) {
    if (!event.isSneaking())
      return;

    tryAccessSession(event.getPlayer(), session -> {
      session.viewer.sendMessage("§aEnding the head-wall session.");
      session.close();
      sessionByPlayerId.remove(session.viewer.getUniqueId());
    });
  }

  @EventHandler
  public void onScroll(PlayerItemHeldEvent event) {
    tryAccessSession(event.getPlayer(), session -> {
      var isForwards = event.getPreviousSlot() < event.getNewSlot();

      if (isForwards) {
        if (!session.nextPage()) {
          session.viewer.sendMessage("§cNo next page");
          return;
        }

        session.viewer.sendMessage("§aNavigated to the next page, at " + session.getCurrentPage() + "/" + session.getNumberOfPages());
        return;
      }

      if (!session.previousPage()) {
        session.viewer.sendMessage("§cNo previous page");
        return;
      }

      session.viewer.sendMessage("§aNavigated to the previous page, at " + session.getCurrentPage() + "/" + session.getNumberOfPages());
    });
  }

  private void tryAccessSession(Player player, Consumer<HeadWallSession> handler) {
    var session = sessionByPlayerId.get(player.getUniqueId());

    if (session != null)
      handler.accept(session);
  }

  public void onShutdown() {
    for (var sessionIterator = sessionByPlayerId.values().iterator(); sessionIterator.hasNext();) {
      sessionIterator.next().close();
      sessionIterator.remove();
    }
  }
}
