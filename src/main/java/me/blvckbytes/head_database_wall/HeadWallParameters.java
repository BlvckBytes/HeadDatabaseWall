package me.blvckbytes.head_database_wall;

import org.bukkit.Material;

public record HeadWallParameters(
  int rows,
  int columns,

  // Defined as the axis-aligned distance between the player's location and the beginning of the
  // wall, onto which heads are then mounted later on, thus not including the head-depth itself.
  int distance,

  Material wallType
) {}
