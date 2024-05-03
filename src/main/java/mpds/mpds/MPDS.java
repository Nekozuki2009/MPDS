package mpds.mpds;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.JsonOps;
import com.mysql.cj.jdbc.exceptions.CommunicationsException;
import mpds.mpds.mixin.HungerManagerAccessor;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.collection.DefaultedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MPDS implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mpds");

	public static Connection connection = null;

	static final List<String> broken = new ArrayList<>();
	
	static Path configjson;
	
	static Map<String, String> config;
	
	public static Gson gson = new Gson();

    @Override
	public void onInitialize() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

		configjson = FabricLoader.getInstance().getConfigDir().resolve("mpdsconfig.json");
		if (Files.notExists(configjson)) {
            try {
                Files.copy(Objects.requireNonNull(MPDS.class.getResourceAsStream("/mpdsconfig.json")), configjson);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
		try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(String.valueOf(configjson)), StandardCharsets.UTF_8))) {
			config = gson.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
		} catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
			connection = DriverManager.getConnection("jdbc:mysql://" + config.get("HOST") + "/" + config.get("DB_NAME") + "?autoReconnect=true", config.get("USER"), config.get("PASSWD"));
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

        try {
			connection.prepareStatement
					("CREATE TABLE IF NOT EXISTS " + config.get("TABLE_NAME") +"(" +
							"id int auto_increment PRIMARY KEY," +
							"Name char(16)," +
							"uuid char(36)," +
							"Air int," +
							"Health float," +
							"enderChestInventory longtext," +
							"exhaustion float," +
							"foodLevel int," +
							"saturationLevel float," +
							"foodTickTimer int," +
							"main longtext," +
							"off longtext," +
							"armor longtext," +
							"selectedSlot int," +
							"experienceLevel int," +
							"experienceProgress int," +
							"sync char(5), " +
							"server text" +
							")").executeUpdate();
		}catch (SQLException e){
			LOGGER.error("FAIL TO CONNECT MYSQL");
			LOGGER.error("DID YOU CHANGE MPDS CONFIG?");
			e.printStackTrace();
		}

		ServerPlayConnectionEvents.JOIN.register(this::onjoin);
		ServerPlayConnectionEvents.DISCONNECT.register(this::ondisconnect);

		LOGGER.info("MPDS loaded");
	}

	private void onjoin(ServerPlayNetworkHandler serverPlayNetworkHandler, PacketSender packetSender, MinecraftServer minecraftServer) {
		new Thread(() -> {
            ServerPlayerEntity player = serverPlayNetworkHandler.getPlayer();
			player.sendSystemMessage(new TranslatableText("loading " + player.getName().getString() + "'s data...").formatted(Formatting.YELLOW), Util.NIL_UUID);
            LOGGER.info("loading " + player.getName().getString() + "'s data...");
			while (true) {
				try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + config.get("TABLE_NAME") + " WHERE uuid = ?")) {
					statement.setString(1, player.getUuid().toString());
					ResultSet resultSet;
					if ((resultSet = statement.executeQuery()).next()) {
						for (int i = 0; "false".equals(resultSet.getString("sync")); i++) {
							if (i == 10) {
								if (config.get("SERVER").equals(resultSet.getString("server"))) {
									player.sendSystemMessage(new TranslatableText("saved " + player.getName().getString() + "'s correct data").formatted(Formatting.AQUA), Util.NIL_UUID);
									LOGGER.info("saved " + player.getName().getString() + "'s correct data");
									player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1f);
									break;
								}
								player.sendSystemMessage(new TranslatableText("IT LOOKS " + player.getName().getString() + "'s DATA WAS BROKEN!").formatted(Formatting.RED), Util.NIL_UUID);
								player.sendSystemMessage(new TranslatableText("PLEASE CONNECT TO " + resultSet.getString("server") + "!").formatted(Formatting.RED), Util.NIL_UUID);
								LOGGER.error("IT LOOKS " + player.getName().getString() + "'s DATA WAS BROKEN!");
								LOGGER.error("PLEASE CONNECT TO " + resultSet.getString("server") + "!");
								player.playSound(SoundEvents.BLOCK_ANVIL_DESTROY, 1f, 1f);
								broken.add(player.getName().getString());
								break;
							}
							Thread.sleep(1000);
							resultSet = statement.executeQuery();
							resultSet.next();
						}
						PreparedStatement befalse = connection.prepareStatement("UPDATE " + config.get("TABLE_NAME") + " SET sync=\"false\" WHERE uuid = ?");
						befalse.setString(1, player.getUuid().toString());
						befalse.executeUpdate();
						player.setAir(resultSet.getInt("Air"));
						player.setHealth(resultSet.getFloat("Health"));
						player.getHungerManager().setExhaustion(resultSet.getFloat("exhaustion"));
						player.getHungerManager().setFoodLevel(resultSet.getInt("foodLevel"));
						player.getHungerManager().setSaturationLevel(resultSet.getFloat("saturationLevel"));
						((HungerManagerAccessor) player.getHungerManager()).setFoodTickTimer(resultSet.getInt("foodTickTimer"));
						player.getInventory().offHand.set(0, ItemStack.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(resultSet.getString("off"))).resultOrPartial(LOGGER::error).orElseThrow());
						player.getInventory().selectedSlot = resultSet.getInt("selectedSlot");
						player.experienceLevel = resultSet.getInt("experienceLevel");
						player.experienceProgress = resultSet.getInt("experienceProgress");
						List.of(resultSet.getString("enderChestInventory").split("&")).forEach(compound -> {
							String[] compounds = compound.split("~");
							player.getEnderChestInventory().setStack(Integer.parseInt(compounds[1]), ItemStack.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(compounds[0])).resultOrPartial(LOGGER::error).orElseThrow());
						});
						List.of(resultSet.getString("main").split("&")).forEach(compound -> {
							String[] compounds = compound.split("~");
							player.getInventory().main.set(Integer.parseInt(compounds[1]), ItemStack.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(compounds[0])).resultOrPartial(LOGGER::error).orElseThrow());
						});
						List.of(resultSet.getString("armor").split("&")).forEach(compound -> {
							String[] compounds = compound.split("~");
							player.getInventory().armor.set(Integer.parseInt(compounds[1]), ItemStack.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(compounds[0])).resultOrPartial(LOGGER::error).orElseThrow());
						});
						player.sendSystemMessage(new TranslatableText("success to load " + player.getName().getString() + "'s data!").formatted(Formatting.AQUA), Util.NIL_UUID);
						LOGGER.info("success to load " + player.getName().getString() + "'s data!");
						player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1f);
					} else {
						PreparedStatement addplayer = connection.prepareStatement("INSERT INTO " + config.get("TABLE_NAME") + " (Name, uuid, sync) VALUES (?, ?, \"false\")");
						addplayer.setString(1, player.getName().getString());
						addplayer.setString(2, player.getUuid().toString());
						addplayer.executeUpdate();
						player.sendSystemMessage(new TranslatableText("COULD NOT FIND " + player.getName().getString() + "'s DATA!").formatted(Formatting.RED), Util.NIL_UUID);
						player.sendSystemMessage(new TranslatableText("MADE NEW ONE!").formatted(Formatting.RED), Util.NIL_UUID);
						LOGGER.warn("COULD NOT FIND " + player.getName().getString() + "'s DATA!");
						LOGGER.warn("MADE NEW ONE!");
						player.playSound(SoundEvents.BLOCK_ANVIL_DESTROY, 1f, 1f);
					}

					PreparedStatement setserver = connection.prepareStatement("UPDATE " + config.get("TABLE_NAME") + " SET server=? WHERE uuid = ?");
					setserver.setString(1, config.get("SERVER"));
					setserver.setString(2, player.getUuid().toString());
					setserver.executeUpdate();
					break;
				} catch (CommunicationsException ignored) {
				} catch (SQLException | InterruptedException e) {
					broken.add(player.getName().getString());
					player.sendSystemMessage(new TranslatableText("THERE WERE SOME ERRORS WHEN LOAD PLAYER DATA").formatted(Formatting.RED), Util.NIL_UUID);
					player.playSound(SoundEvents.BLOCK_ANVIL_DESTROY, 1f, 1f);
					LOGGER.error("THERE WERE SOME ERRORS WHEN LOAD PLAYER DATA:");
					e.printStackTrace();
					break;
				}
			}
        }).start();
    }

	private void ondisconnect(ServerPlayNetworkHandler serverPlayNetworkHandler, MinecraftServer minecraftServer) {
		new Thread(() -> {
			ServerPlayerEntity player = serverPlayNetworkHandler.getPlayer();
			LOGGER.info("saving " + player.getName().getString() + "'s data...");

			if (broken.stream().anyMatch(bplayer -> bplayer.equals(player.getName().getString()))) {
				LOGGER.warn("skip saving because " + player.getName().getString() + "'s data was broken");
				broken.remove(player.getName().getString());
				return;
			}

			while (true) {
				try (PreparedStatement statement = connection.prepareStatement("UPDATE " + config.get("TABLE_NAME") + " SET Air=?, Health=?, enderChestInventory=?, exhaustion=?, foodLevel=?, saturationLevel=?, foodTickTimer=?, main=?, off=?, armor=?, selectedSlot=?, experienceLevel=?, experienceProgress=?, sync=\"true\" where uuid=?")) {
					statement.setInt(1, player.getAir());
					statement.setFloat(2, player.getHealth());
					statement.setFloat(4, player.getHungerManager().getExhaustion());
					statement.setInt(5, player.getHungerManager().getFoodLevel());
					statement.setFloat(6, player.getHungerManager().getSaturationLevel());
					statement.setInt(7, ((HungerManagerAccessor) player.getHungerManager()).getFoodTickTimer());
					StringBuilder off = new StringBuilder();
					if (player.getInventory().offHand.get(0).isEmpty()) {
						off.append("{\"id\":\"minecraft:air\",\"Count\":0}");
					} else {
						off.append(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, player.getInventory().offHand.get(0)).resultOrPartial(LOGGER::error).orElseThrow());
					}
					statement.setString(9, off.toString());
					statement.setInt(11, player.getInventory().selectedSlot);
					statement.setInt(12, player.experienceLevel);
					statement.setFloat(13, player.experienceProgress);
					statement.setString(14, player.getUuidAsString());
					EnderChestInventory end = player.getEnderChestInventory();
					StringBuilder endresults = new StringBuilder();
					for (int i = 0; i < end.size(); i++) {
						if (end.getStack(i).isEmpty()) {
							endresults.append("{\"id\":\"minecraft:air\",\"Count\":0}~").append(i).append("&");
						} else {
							endresults.append(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, end.getStack(i)).resultOrPartial(LOGGER::error).orElseThrow()).append("~").append(i).append("&");
						}
					}
					statement.setString(3, endresults.toString());
					DefaultedList<ItemStack> main = player.getInventory().main;
					StringBuilder mainresults = new StringBuilder();
					for (int i = 0; i < main.size(); i++) {
						if (main.get(i).isEmpty()) {
							mainresults.append("{\"id\":\"minecraft:air\",\"Count\":0}~").append(i).append("&");
						} else {
							mainresults.append(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, main.get(i)).resultOrPartial(LOGGER::error).orElseThrow()).append("~").append(i).append("&");
						}
					}
					statement.setString(8, mainresults.toString());
					DefaultedList<ItemStack> armor = player.getInventory().armor;
					StringBuilder armorresults = new StringBuilder();
					for (int i = 0; i < armor.size(); i++) {
						if (armor.get(i).isEmpty()) {
							armorresults.append("{\"id\":\"minecraft:air\",\"Count\":0}~").append(i).append("&");
						} else {
							armorresults.append(ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, armor.get(i)).resultOrPartial(LOGGER::error).orElseThrow()).append("~").append(i).append("&");
						}
					}
					statement.setString(10, armorresults.toString());
					statement.executeUpdate();
					LOGGER.info("success to save " + player.getName().getString() + "'s data");
					break;
				} catch (CommunicationsException ignored) {
				} catch (SQLException e) {
					broken.remove(player.getName().getString());
					LOGGER.error("FAIL TO SAVE " + player.getName().getString() + "'s DATA:");
					e.printStackTrace();
					break;
				}
			}
		}).start();
	}
}