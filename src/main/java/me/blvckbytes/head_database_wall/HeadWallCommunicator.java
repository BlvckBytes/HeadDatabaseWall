package me.blvckbytes.head_database_wall;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

public interface HeadWallCommunicator {

  void sendBlockChange(Player player, Location location, BlockData blockData);

  void updateBlockToTexturedSkull(Player player, BlockFace mountingFace, Location location, String base64Textures);

}
