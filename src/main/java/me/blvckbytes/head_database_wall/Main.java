package me.blvckbytes.head_database_wall;

public class Main {

  public static void main(String[] args) {
    int x = 128;

    System.out.println(x & (1 << Integer.SIZE - 1));
  }
}
