# Minecraft Player Data Sync(MPDS)
## Description
it's a fabric mod to sync player data between fabric servers. this needs only server side. this mod can sync player's air, health, enderChest, exhaustion, foodLevel, saturationLevel, foodTickTimer, inventory, offhand, armor, selectedSlot, experienceLevel, and experienceProgress. 
## TODO
> [!CAUTION]
> - **you need to build mysql server.**
> - **you need to link fabric servers with proxy server!(like velocity, bungeecord, etc...)**
1. add this mod to server mods folder.
1. restart server.
1. edit config file which is in config/mpdsconfig file.
1. let's play!
## config file
```
{
  "_Hcomment_" : "it's mysql host ip",
  "HOST" : "000.000.000.000",
  "_Dcomment_" : "it's mysql database name **YOU MUST CREATE THIS DB!!**",
  "DB_NAME" : "test",
  "_Tcomment_" : "it's mysql table name(auto create)",
  "TABLE_NAME" : "test",
  "_Ucomment_" : "it's mysql user name",
  "USER" : "test",
  "_Pcomment_" : "it's mysql user's password",
  "PASSWD" : "test",
  "_Scomment_" : "it's this server name",
  "SERVER" : "s"
}
```
## Released on [modrinth](https://modrinth.com/mod/mpds/)
