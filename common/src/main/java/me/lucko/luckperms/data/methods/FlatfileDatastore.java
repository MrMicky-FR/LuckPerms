package me.lucko.luckperms.data.methods;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.lucko.luckperms.LuckPermsPlugin;
import me.lucko.luckperms.data.Datastore;
import me.lucko.luckperms.groups.Group;
import me.lucko.luckperms.tracks.Track;
import me.lucko.luckperms.users.User;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SuppressWarnings({"ResultOfMethodCallIgnored", "UnnecessaryLocalVariable"})
public class FlatfileDatastore extends Datastore {

    private Map<String, String> uuidCache = new ConcurrentHashMap<>();

    private final File pluginDir;
    private File usersDir;
    private File groupsDir;
    private File tracksDir;
    private File uuidData;

    public FlatfileDatastore(LuckPermsPlugin plugin, File pluginDir) {
        super(plugin, "Flatfile - JSON");
        this.pluginDir = pluginDir;
    }

    private boolean doWrite(File file, WriteOperation writeOperation) {
        boolean success = false;

        FileWriter fileWriter = null;
        BufferedWriter bufferedWriter = null;
        JsonWriter jsonWriter = null;

        try {
            fileWriter = new FileWriter(file);
            bufferedWriter = new BufferedWriter(fileWriter);
            jsonWriter = new JsonWriter(bufferedWriter);
            jsonWriter.setIndent("    ");
            success = writeOperation.onRun(jsonWriter);
            jsonWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            close(jsonWriter);
            close(bufferedWriter);
            close(fileWriter);
        }

        return success;
    }

    private boolean doRead(File file, ReadOperation readOperation) {
        boolean success = false;

        FileReader fileReader = null;
        BufferedReader bufferedReader = null;
        JsonReader jsonReader = null;

        try {
            fileReader = new FileReader(file);
            bufferedReader = new BufferedReader(fileReader);
            jsonReader = new JsonReader(bufferedReader);
            success = readOperation.onRun(jsonReader);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            close(jsonReader);
            close(bufferedReader);
            close(fileReader);
        }

        return success;
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void init() {
        try {
            makeFiles();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        uuidCache.putAll(getUUIDCache());
        setAcceptingLogins(true);
    }

    private void makeFiles() throws IOException {
        File data = new File(pluginDir, "data");
        data.mkdirs();

        usersDir = new File(data, "users");
        usersDir.mkdir();

        groupsDir = new File(data, "groups");
        groupsDir.mkdir();

        tracksDir = new File(data, "tracks");
        tracksDir.mkdir();

        uuidData = new File(data, "uuidcache.txt");
        uuidData.createNewFile();
    }

    @Override
    public void shutdown() {
        saveUUIDCache(uuidCache);
    }

    @Override
    public boolean loadOrCreateUser(UUID uuid, String username) {
        User user = plugin.getUserManager().makeUser(uuid, username);

        File userFile = new File(usersDir, uuid.toString() + ".json");
        if (!userFile.exists()) {
            try {
                userFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            plugin.getUserManager().giveDefaults(user);

            boolean success = doWrite(userFile, writer -> {
                writer.beginObject();
                writer.name("uuid").value(user.getUuid().toString());
                writer.name("name").value(user.getName());
                writer.name("primaryGroup").value(user.getPrimaryGroup());
                writer.name("perms");
                writer.beginObject();
                for (Map.Entry<String, Boolean> e : user.getNodes().entrySet()) {
                    writer.name(e.getKey()).value(e.getValue().booleanValue());
                }
                writer.endObject();
                writer.endObject();
                return true;
            });

            if (!success) return false;
        }

        boolean success = doRead(userFile, reader -> {
            reader.beginObject();
            reader.nextName(); // uuid record
            reader.nextString(); // uuid
            reader.nextName(); // name record
            reader.nextString(); // name
            reader.nextName(); // primaryGroup record
            user.setPrimaryGroup(reader.nextString()); // primaryGroup
            reader.nextName(); //perms
            reader.beginObject();
            while (reader.hasNext()) {
                String node = reader.nextName();
                boolean b = reader.nextBoolean();
                user.getNodes().put(node, b);
            }

            reader.endObject();
            reader.endObject();
            return true;
        });

        if (success) plugin.getUserManager().updateOrSetUser(user);
        return success;
    }

    @Override
    public boolean loadUser(UUID uuid) {
        User user = plugin.getUserManager().makeUser(uuid);

        File userFile = new File(usersDir, uuid.toString() + ".json");
        if (!userFile.exists()) {
            return false;
        }

        boolean success = doRead(userFile, reader -> {
            reader.beginObject();
            reader.nextName(); // uuid record
            reader.nextString(); // uuid
            reader.nextName(); // name record
            user.setName(reader.nextString()); // name
            reader.nextName(); // primaryGroup record
            user.setPrimaryGroup(reader.nextString()); // primaryGroup
            reader.nextName(); // perms record
            reader.beginObject();
            while (reader.hasNext()) {
                String node = reader.nextName();
                boolean b = reader.nextBoolean();
                user.getNodes().put(node, b);
            }

            reader.endObject();
            reader.endObject();
            return true;
        });

        if (success) plugin.getUserManager().updateOrSetUser(user);
        return success;
    }

    @Override
    public boolean saveUser(User user) {
        File userFile = new File(usersDir, user.getUuid().toString() + ".json");
        if (!userFile.exists()) {
            try {
                userFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        boolean success = doWrite(userFile, writer -> {
            writer.beginObject();
            writer.name("uuid").value(user.getUuid().toString());
            writer.name("name").value(user.getName());
            writer.name("primaryGroup").value(user.getPrimaryGroup());
            writer.name("perms");
            writer.beginObject();
            for (Map.Entry<String, Boolean> e : user.getNodes().entrySet()) {
                writer.name(e.getKey()).value(e.getValue().booleanValue());
            }
            writer.endObject();
            writer.endObject();
            return true;
        });
        return success;
    }

    @Override
    public boolean createAndLoadGroup(String name) {
        Group group = plugin.getGroupManager().makeGroup(name);

        File groupFile = new File(groupsDir, name + ".json");
        if (!groupFile.exists()) {
            try {
                groupFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            boolean success = doWrite(groupFile, writer -> {
                writer.beginObject();
                writer.name("name").value(group.getName());
                writer.name("perms");
                writer.beginObject();
                for (Map.Entry<String, Boolean> e : group.getNodes().entrySet()) {
                    writer.name(e.getKey()).value(e.getValue().booleanValue());
                }
                writer.endObject();
                writer.endObject();
                return true;
            });

            if (!success) return false;
        }

        boolean success = doRead(groupFile, reader -> {
            reader.beginObject();
            reader.nextName(); // name record
            reader.nextString(); // name
            reader.nextName(); //perms
            reader.beginObject();
            while (reader.hasNext()) {
                String node = reader.nextName();
                boolean b = reader.nextBoolean();
                group.getNodes().put(node, b);
            }

            reader.endObject();
            reader.endObject();
            return true;
        });

        if (success) plugin.getGroupManager().updateOrSetGroup(group);
        return success;
    }

    @Override
    public boolean loadGroup(String name) {
        Group group = plugin.getGroupManager().makeGroup(name);

        File groupFile = new File(groupsDir, name + ".json");
        if (!groupFile.exists()) {
            return false;
        }

        boolean success = doRead(groupFile, reader -> {
            reader.beginObject();
            reader.nextName(); // name record
            reader.nextString(); // name
            reader.nextName(); //perms
            reader.beginObject();
            while (reader.hasNext()) {
                String node = reader.nextName();
                boolean b = reader.nextBoolean();
                group.getNodes().put(node, b);
            }

            reader.endObject();
            reader.endObject();
            return true;
        });

        if (success) plugin.getGroupManager().updateOrSetGroup(group);
        return success;
    }

    @Override
    public boolean loadAllGroups() {
        String[] fileNames = groupsDir.list((dir, name) -> name.endsWith(".json"));
        if (fileNames == null) return false;
        List<String> groups = Arrays.stream(fileNames).map(s -> s.substring(0, s.length() - 5)).collect(Collectors.toList());

        plugin.getGroupManager().unloadAll();
        groups.forEach(this::loadGroup);
        return true;
    }

    @Override
    public boolean saveGroup(Group group) {
        File groupFile = new File(groupsDir, group.getName() + ".json");
        if (!groupFile.exists()) {
            try {
                groupFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        boolean success = doWrite(groupFile, writer -> {
            writer.beginObject();
            writer.name("name").value(group.getName());
            writer.name("perms");
            writer.beginObject();
            for (Map.Entry<String, Boolean> e : group.getNodes().entrySet()) {
                writer.name(e.getKey()).value(e.getValue().booleanValue());
            }
            writer.endObject();
            writer.endObject();
            return true;
        });

        return success;
    }

    @Override
    public boolean deleteGroup(Group group) {
        File groupFile = new File(groupsDir, group.getName() + ".json");
        if (groupFile.exists()) {
            groupFile.delete();
        }
        return true;
    }

    @Override
    public boolean createAndLoadTrack(String name) {
        Track track = plugin.getTrackManager().makeTrack(name);
        List<String> groups = new ArrayList<>();

        File trackFile = new File(tracksDir, name + ".json");
        if (!trackFile.exists()) {
            try {
                trackFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            boolean success = doWrite(trackFile, writer -> {
                writer.beginObject();
                writer.name("name").value(track.getName());
                writer.name("groups");
                writer.beginArray();
                for (String s : track.getGroups()) {
                    writer.value(s);
                }
                writer.endArray();
                writer.endObject();
                return true;
            });

            if (!success) return false;
        }

        boolean success = doRead(trackFile, reader -> {
            reader.beginObject();
            reader.nextName(); // name record
            reader.nextString(); // name
            reader.nextName(); // groups record
            reader.beginArray();
            while (reader.hasNext()) {
                groups.add(reader.nextString());
            }
            reader.endArray();
            reader.endObject();
            return true;
        });

        track.setGroups(groups);
        if (success) plugin.getTrackManager().updateOrSetTrack(track);
        return success;
    }

    @Override
    public boolean loadTrack(String name) {
        Track track = plugin.getTrackManager().makeTrack(name);
        List<String> groups = new ArrayList<>();

        File trackFile = new File(tracksDir, name + ".json");
        if (!trackFile.exists()) {
            return false;
        }

        boolean success = doRead(trackFile, reader -> {
            reader.beginObject();
            reader.nextName(); // name record
            reader.nextString(); // name
            reader.nextName(); // groups record
            reader.beginArray();
            while (reader.hasNext()) {
                groups.add(reader.nextString());
            }
            reader.endArray();
            reader.endObject();
            return true;
        });

        track.setGroups(groups);
        if (success) plugin.getTrackManager().updateOrSetTrack(track);
        return success;
    }

    @Override
    public boolean loadAllTracks() {
        String[] fileNames = tracksDir.list((dir, name) -> name.endsWith(".json"));
        if (fileNames == null) return false;
        List<String> tracks = Arrays.stream(fileNames).map(s -> s.substring(0, s.length() - 5)).collect(Collectors.toList());

        plugin.getTrackManager().unloadAll();
        tracks.forEach(this::loadTrack);
        return true;
    }

    @Override
    public boolean saveTrack(Track track) {
        File trackFile = new File(tracksDir, track.getName() + ".json");
        if (!trackFile.exists()) {
            try {
                trackFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        boolean success = doWrite(trackFile, writer -> {
            writer.beginObject();
            writer.name("name").value(track.getName());
            writer.name("groups");
            writer.beginArray();
            for (String s : track.getGroups()) {
                writer.value(s);
            }
            writer.endArray();
            writer.endObject();
            return true;
        });

        return success;
    }

    @Override
    public boolean deleteTrack(Track track) {
        File trackFile = new File(tracksDir, track.getName() + ".json");
        if (trackFile.exists()) {
            trackFile.delete();
        }
        return true;
    }

    private Map<String, String> getUUIDCache() {
        Map<String, String> cache = new HashMap<>();

        FileReader fileReader = null;
        BufferedReader bufferedReader = null;

        try {
            fileReader = new FileReader(uuidData);
            bufferedReader = new BufferedReader(fileReader);

            Properties props = new Properties();
            props.load(bufferedReader);
            for (String key : props.stringPropertyNames()) {
                cache.put(key, props.getProperty(key));
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            close(bufferedReader);
            close(fileReader);
        }

        return cache;
    }

    private void saveUUIDCache(Map<String, String> cache) {
        FileWriter fileWriter = null;
        BufferedWriter bufferedWriter = null;

        try {
            fileWriter = new FileWriter(uuidData);
            bufferedWriter = new BufferedWriter(fileWriter);

            Properties properties = new Properties();
            properties.putAll(cache);
            properties.store(bufferedWriter, null);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            close(bufferedWriter);
            close(fileWriter);
        }
    }

    @Override
    public boolean saveUUIDData(String username, UUID uuid) {
        uuidCache.put(username, uuid.toString());
        return true;
    }

    @Override
    public UUID getUUID(String username) {
        if (uuidCache.get(username) == null) return null;
        return UUID.fromString(uuidCache.get(username));
    }

    interface WriteOperation {
        boolean onRun(JsonWriter writer) throws IOException;
    }

    interface ReadOperation {
        boolean onRun(JsonReader reader) throws IOException;
    }
}