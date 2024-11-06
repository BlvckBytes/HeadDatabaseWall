package me.blvckbytes.head_database_wall;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import me.arcaniax.hdb.object.head.Head;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

public class HeadWallSessionRegistry extends PacketAdapter implements Listener {

  private static final long INTERACTION_MIN_DISTANCE_MS = 250; // Up to 4 heads/second will suffice, I believe, x)
  private static final double REMOVAL_DISTANCE_BLOCKS = 10;
  private static final double REMOVAL_DISTANCE_BLOCKS_SQUARED = REMOVAL_DISTANCE_BLOCKS * REMOVAL_DISTANCE_BLOCKS;

  private static final HeadWallParameters WALL_PARAMETER = new HeadWallParameters(5, 8, 3, Material.COAL_BLOCK);

  private final Map<UUID, HeadWallSession> sessionByPlayerId;

  private final ProtocolManager protocolManager;

  private long lastProcessedInteractionStamp;

  public HeadWallSessionRegistry(Plugin plugin, ProtocolManager protocolManager) {
    super(
      plugin, ListenerPriority.HIGHEST,
      PacketType.Play.Client.BLOCK_DIG,
      PacketType.Play.Client.USE_ITEM_ON,
      PacketType.Play.Client.USE_ITEM
    );

    this.sessionByPlayerId = new HashMap<>();
    this.protocolManager = protocolManager;
  }

  @Override
  public void onPacketReceiving(PacketEvent event) {
    Player player;

    if ((player = event.getPlayer()) == null)
      return;

    var session = sessionByPlayerId.get(player.getUniqueId());

    if (session == null)
      return;

    boolean wasLeft = false;
    Location interactionLocation = null;

    int blockChangeAckId;

    if (PacketType.Play.Server.BLOCK_CHANGED_ACK.isSupported())
      blockChangeAckId = event.getPacket().getIntegers().read(0);
    else
      blockChangeAckId = -1;

    // Block break; left-click
    // Let's not go into as much detail as to figure out whether the block actually broke, just update it regardless.
    if (event.getPacket().getType() == PacketType.Play.Client.BLOCK_DIG) {
      var position = event.getPacket().getBlockPositionModifier().read(0);

      interactionLocation = new Location(
        session.viewer.getWorld(),
        position.getX(), position.getY(), position.getZ()
      );

      session.onTryBlockManipulate(interactionLocation, blockChangeAckId);
      wasLeft = true;
    }

    // Block place or interaction; right-click
    else if (event.getPacket().getType() == PacketType.Play.Client.USE_ITEM_ON) {
      var movingPosition = event.getPacket().getMovingBlockPositions().read(0);
      var position = movingPosition.getBlockPosition();

      interactionLocation = new Location(
        session.viewer.getWorld(),
        position.getX(),
        position.getY(),
        position.getZ()
      );

      var mainHandItem = session.viewer.getInventory().getItemInMainHand();
      var mainHandItemType = mainHandItem.getType();
      var doesBuild = !mainHandItemType.isAir() && mainHandItemType.isBlock();

      if (doesBuild) {
        var blockFace = protocolLibDirectionToBlockPosition(movingPosition.getDirection());

        session.onTryBlockManipulate(
          interactionLocation.clone().add(
            blockFace.getModX(),
            blockFace.getModY(),
            blockFace.getModZ()
          ),
          blockChangeAckId
        );

        // Force inventory-update, as to re-set the stack-size
        session.viewer.getInventory().setItemInMainHand(mainHandItem);
      }

      else
        session.onTryBlockManipulate(interactionLocation, blockChangeAckId);
    }

    // else: USE_ITEM -> Interaction into air with item in hand, just cancel

    event.setCancelled(true);

    if (interactionLocation == null)
      return;

    // Since this is called based on received packets, and interactions may fire multiple times
    // within a short time-span, debounce relaying this call to the underlying implementation.

    if (System.currentTimeMillis() - lastProcessedInteractionStamp < INTERACTION_MIN_DISTANCE_MS)
      return;

    onSessionInteract(session, interactionLocation, wasLeft);
    lastProcessedInteractionStamp = System.currentTimeMillis();
  }

  private BlockFace protocolLibDirectionToBlockPosition(EnumWrappers.Direction direction) {
    return switch (direction) {
      case DOWN -> BlockFace.DOWN;
      case UP -> BlockFace.UP;
      case NORTH -> BlockFace.NORTH;
      case SOUTH -> BlockFace.SOUTH;
      case WEST -> BlockFace.WEST;
      case EAST -> BlockFace.EAST;
    };
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

    var session = new HeadWallSession(player, heads, WALL_PARAMETER, protocolManager);

    this.sessionByPlayerId.put(playerId, session);
    return session;
  }

  private void onSessionInteract(HeadWallSession session, Location location, boolean wasLeft) {
    var correspondingHead = session.getHeadAtLocation(location);

    if (correspondingHead == null) {
      session.viewer.sendMessage("§cPlease click directly on a head; left-click to request, right-click for information, sneak to exit");
      return;
    }

    if (wasLeft) {
      session.viewer.getInventory().addItem(correspondingHead.getHead());
      session.viewer.sendMessage("§aYou've been given the head " + correspondingHead.name);
      return;
    }

    session.viewer.sendMessage("§8§m                              ");
    session.viewer.sendMessage("§aName: " + correspondingHead.name);
    session.viewer.sendMessage("§aCategory: " + correspondingHead.c.name());
    session.viewer.sendMessage("§aTags: " + String.join(", ", correspondingHead.tags));
    session.viewer.sendMessage("§8§m                              ");
  }

  @EventHandler
  public void onBreak(BlockBreakEvent event) {
    tryAccessSession(event.getPlayer(), session -> event.setCancelled(true));
  }

  @EventHandler
  public void onPlace(BlockPlaceEvent event) {
    tryAccessSession(event.getPlayer(), session -> event.setCancelled(true));
  }

  @EventHandler
  public void onBucketEmpty(PlayerBucketEmptyEvent event) {
    tryAccessSession(event.getPlayer(), session -> event.setCancelled(true));
  }

  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    tryAccessSession(event.getPlayer(), session -> event.setCancelled(true));
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
      if (session.getNumberOfPages() == 1)
        return;

      var isForwards = event.getPreviousSlot() < event.getNewSlot();

      // TODO: Maybe wrap-around here, to quickly access the last page?

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
