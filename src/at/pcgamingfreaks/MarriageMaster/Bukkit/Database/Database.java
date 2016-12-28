/*
 *   Copyright (C) 2016 GeorgH93
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package at.pcgamingfreaks.MarriageMaster.Bukkit.Database;

import at.pcgamingfreaks.ConsoleColor;
import at.pcgamingfreaks.MarriageMaster.Bukkit.API.Marriage;
import at.pcgamingfreaks.MarriageMaster.Bukkit.Database.FilesMigrator.Converter;
import at.pcgamingfreaks.MarriageMaster.Bukkit.Database.FilesMigrator.MigrationMarriage;
import at.pcgamingfreaks.MarriageMaster.Bukkit.Database.FilesMigrator.MigrationPlayer;
import at.pcgamingfreaks.MarriageMaster.Bukkit.Database.UnCacheStrategies.UnCacheStrategie;
import at.pcgamingfreaks.MarriageMaster.Bukkit.MarriageMaster;
import at.pcgamingfreaks.MarriageMaster.Database.BaseDatabase;
import at.pcgamingfreaks.PluginLib.Bukkit.PluginLib;
import at.pcgamingfreaks.PluginLib.Database.DatabaseConnectionPool;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public abstract class Database extends BaseDatabase<MarriageMaster, MarriagePlayerData, Marriage> implements Listener
{
	private final UnCacheStrategie unCacheStrategie;

	protected Database(MarriageMaster plugin)
	{
		super(plugin, plugin.getLogger(), plugin.getConfiguration().useUUIDs(), plugin.getConfiguration().useUUIDSeparators(), plugin.getConfiguration().getUseOnlineUUIDs());
		unCacheStrategie  = UnCacheStrategie.getUnCacheStrategie(this);
	}

	public static Database getDatabase(MarriageMaster plugin)
	{
		try
		{
			Database db;
			switch(plugin.getConfiguration().getDatabaseType())
			{
				case "mysql": db = new MySQL(plugin); break;
				case "sqlite": db = new SQLite(plugin); break;
				case "external":
				case "global":
				case "shared":
					DatabaseConnectionPool pool = PluginLib.getInstance().getDatabaseConnectionPool();
					if(pool == null)
					{
						plugin.getLogger().warning(ConsoleColor.RED + "The shared connection pool is not initialized correctly!" + ConsoleColor.RESET);
						return null;
					}
					switch(pool.getDatabaseType().toLowerCase())
					{
						case "mysql": db = new MySQLShared(plugin, pool); break;
						case "sqlite": db = new SQLiteShared(plugin, pool); break;
						default: plugin.getLogger().warning(ConsoleColor.RED + "The database type of the shared pool is currently not supported!" + ConsoleColor.RESET); return null;
					}
					break;
				case "file":
				case "files":
				case "flat": plugin.getLogger().info(MESSAGE_FILES_NO_LONGER_SUPPORTED);
					db = new SQLite(plugin);
					Converter.runConverter(plugin, db);
					break;
				default: plugin.getLogger().warning(String.format(MESSAGE_UNKNOWN_DB_TYPE,  plugin.getConfiguration().getDatabaseType())); return null;
			}
			db.startup();
			return db;
		}
		catch(Exception ignored){ ignored.printStackTrace(); } //TODO remove stacktrace after beta
		return null;
	}

	@Override
	protected void startup() throws Exception
	{
		super.startup();
		Bukkit.getServer().getPluginManager().registerEvents(this, plugin);
	}

	@Override
	public void close()
	{
		unCacheStrategie.close(); // Killing the uncache strategie
		super.close();
		HandlerList.unregisterAll(this);
	}

	@SuppressWarnings("deprecation")
	protected UUID getUUIDFromIdentifier(String identifier)
	{
		if(useUUIDs)
		{
			return UUID.fromString((useUUIDSeparators) ? identifier : identifier.replaceAll("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
		}
		else
		{
			return Bukkit.getOfflinePlayer(identifier).getUniqueId();
		}
	}

	@Override
	public MarriagePlayerData getPlayer(UUID uuid)
	{
		MarriagePlayerData player = players.get(uuid);
		if(player == null)
		{
			// We cache all our married players on startup, we also load unmarried players on join. If there is no data for him in the cache we return a new player.
			// It's very likely that he was only requested in order to show a info about his marriage status. When someone change the player the database will fix him anyway.
			player = new MarriagePlayerData(Bukkit.getOfflinePlayer(uuid));
			cache(player); // Let's put the new player into the cache
			load(player);
		}
		return player;
	}

	public Collection<Marriage> getMarriages()
	{
		return marriages;
	}

	@EventHandler(priority = EventPriority.LOWEST) // We want to start the loading of the player as soon as he connects, so he probably is ready as soon as someone requests the player.
	public void onPlayerLoginEvent(PlayerJoinEvent event) // This will load the player if he isn't loaded yet
	{
		UUID uuid = event.getPlayer().getUniqueId();
		MarriagePlayerData player = players.get(uuid);
		if(player == null)
		{
			// We cache all our married players on startup, we also load unmarried players on join. If there is no data for him in the cache we return a new player.
			// It's very likely that he was only requested in order to show a info about his marriage status. When someone change the player the database will fix him anyway.
			player = new MarriagePlayerData(Bukkit.getOfflinePlayer(uuid));
			cache(player); // Let's put the new player into the cache
		}
		load(player);
	}

	public void cachedSurnameUpdate(MarriageData marriage, String oldSurname)
	{
		cachedSurnameUpdate(marriage, oldSurname, true);
	}

	public void cachedSurnameUpdate(MarriageData marriage, String oldSurname, boolean updateDatabase)
	{
		if(updateDatabase) updateSurname(marriage);
		if(oldSurname != null && !oldSurname.isEmpty())
		{
			surnames.remove(oldSurname);
		}
		if(marriage.getSurname() != null && !marriage.getSurname().isEmpty())
		{
			surnames.put(marriage.getSurname(), marriage);
		}
	}

	public void cachedDivorce(MarriageData marriage)
	{
		cachedDivorce(marriage, true);
	}

	public void cachedDivorce(MarriageData marriage, boolean updateDatabase)
	{
		if(updateDatabase) divorce(marriage);
		if(marriage.getSurname() != null && !marriage.getSurname().isEmpty())
		{
			surnames.remove(marriage.getSurname());
		}
		unCache(marriage);
	}

	public void cachedMarry(MarriageData marriage)
	{
		marry(marriage);
		if(marriage.getSurname() != null && !marriage.getSurname().isEmpty())
		{
			surnames.put(marriage.getSurname(), marriage);
		}
		cache(marriage);
	}

	protected boolean supportBungee()
	{
		return false;
	}

	public boolean useBungee()
	{
		return false;
	}

	@Override
	protected void runAsync(@NotNull Runnable runnable, long delay)
	{
		if(delay < 1)
		{
			Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
		}
		else
		{
			Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delay);
		}
	}

	//region Functions that have to be implemented by the real database
	public abstract void updateHome(final MarriageData marriage);

	public abstract void updatePvPState(final MarriageData marriage);

	public abstract void updateBackpackShareState(final MarriagePlayerData player);

	public abstract void updatePriestStatus(final MarriagePlayerData player);

	public abstract void migratePlayer(final MigrationPlayer player);

	public abstract void migrateMarriage(final MigrationMarriage marriage);

	protected abstract void updateSurname(final MarriageData marriage);

	protected abstract void divorce(final MarriageData marriage);

	protected abstract void marry(final MarriageData marriage);
	//endregion
}