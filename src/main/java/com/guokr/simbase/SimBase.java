package com.guokr.simbase;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wahlque.net.action.ActionRegistry;
import org.wahlque.net.server.Server;

import com.esotericsoftware.yamlbeans.YamlReader;
import com.guokr.simbase.action.AddAction;
import com.guokr.simbase.action.DelAction;
import com.guokr.simbase.action.ExitAction;
import com.guokr.simbase.action.GetAction;
import com.guokr.simbase.action.PingAction;
import com.guokr.simbase.action.PutAction;
import com.guokr.simbase.action.SaveAction;
import com.guokr.simbase.action.ShutdownAction;

public class SimBase {

	private static final String dir = System.getProperty("user.dir")
			+ System.getProperty("file.separator");
	private static final String idxFilePath = dir + "keys.idx";
	private static final Logger logger = LoggerFactory.getLogger(SimBase.class);
	private long timeInterval;
	private int port;
	private Map<String, Object> config;

	private Map<String, SimEngine> base = new HashMap<String, SimEngine>();

	public SimBase(Map<String, Object> config) {
		this.config = config;
		this.timeInterval = Long.parseLong((String) config.get("CRONINTERVAL"));
		this.port = Integer.parseInt((String) config.get("PORT"));
		this.load();// 新建时加载磁盘数据
		this.cron();// 设置定时任务
	}

	public void clear() {
		List<String> list = new ArrayList<String>(base.keySet());
		if (list.size() != 0) {
			Collections.shuffle(list);
			base.get(list.get(0)).clear();
		} else {
			logger.warn("Empty set do not need clear");
		}
	}

	public void load() {// 只有全局读取的时候读取文件里的map
		try {
			BufferedReader input = new BufferedReader(new FileReader(
					idxFilePath));
			String[] keys = input.readLine().split("\\|");
			for (String key : keys) {
				logger.info("Loading key: " + key);// 只有存储才有多进程的情况
				this.load(key);
			}
			input.close();
		} catch (FileNotFoundException e) {
			logger.warn("Backup .idx file not found.Please examine your backup file");
			return;
		} catch (NullPointerException e) {
			logger.warn("Backup .idx file is empty.Please examine your backup file");
			return;
		} catch (Throwable e) {
			throw new SimBaseException(e);
		}
	}

	public void load(String key) {
		if (!base.containsKey(key)) {
			base.put(key, new SimEngine(config));
		}
		try {
			base.get(key).load(key);
		} catch (FileNotFoundException e) {
			logger.warn("Backup .dmp file not found,Please examine your backup file");
			return;
		}
	}

	public void save() {// 只有全局保存的时候把map写到文件里

		String keys = "";

		if (!base.keySet().isEmpty()) {
			FileWriter output = null;
			try {
				output = new FileWriter(idxFilePath);
				for (String key : base.keySet()) {
					keys += key + "|";
					logger.info("Push task:Save key-- " + key + " to queue");
					this.save(key);
					logger.info("Push finish");
				}
				keys = keys.substring(0, keys.length() - 1);
				output.write(keys, 0, keys.length());

			} catch (Throwable e) {
				throw new SimBaseException(e);
			} finally {
				if (output != null) {
					try {
						output.close();
					} catch (IOException e) {
						throw new SimBaseException(e);
					}
				}
			}
		} else {
			logger.warn("Empty set don't need save");
		}
	}

	public void save(String key) {
		if (base.containsKey(key)) {
			try {
				base.get(key).save(key);
			} catch (Throwable e) {
				throw new SimBaseException(e);
			}
		}
	}

	public void delete(String key, int docid) {
		try {
			base.get(key).delete(docid);
		} catch (Throwable e) {
			throw new SimBaseException(e);// 如果没有键值直接抛错
		}
	}

	public void add(String key, int docid, float[] distr) {
		if (!base.containsKey(key)) {
			base.put(key, new SimEngine(this.config));
		}
		base.get(key).add(docid, distr);
	}

	public void update(String key, int docid, float[] distr) {
		if (!base.containsKey(key)) {
			base.put(key, new SimEngine(config));
		}
		base.get(key).update(docid, distr);
	}

	public void cron() {
		// 创建一个cron任务
		Timer cron = new Timer();

		TimerTask cleartask = new TimerTask() {
			public void run() {
				clear();
			}
		};
		cron.schedule(cleartask, timeInterval / 2, timeInterval);

		TimerTask savetask = new TimerTask() {
			public void run() {
				save();
			}
		};
		cron.schedule(savetask, timeInterval, timeInterval);
	}

	public SortedSet<Map.Entry<Integer, Float>> retrieve(String key, int docid) {
		SortedSet<Map.Entry<Integer, Float>> result = null;
		if (base.containsKey(key)) {
			result = base.get(key).retrieve(docid);
		} else {
			result = SimTable
					.entriesSortedByValues(new TreeMap<Integer, Float>());
		}
		return result;
	}

	public static void main(String[] args) throws IOException {
		Map<String, Object> config = new HashMap<String, Object>();
		try {
			YamlReader yaml = new YamlReader(new FileReader(dir
					+ "/config/simBaseServer.yaml"));
			config = (Map<String, Object>) yaml.read();
		} catch (IOException e) {
			logger.warn("YAML not found,loading default config");
			config.put("timeInterval", "120000");
			config.put("port", "7654");
		}
		try {
			Map<String, Object> context = new HashMap<String, Object>(config);
			context.put("debug", true);
			SimBase db = new SimBase(config);
			context.put("simbase", db);

			ActionRegistry registry = ActionRegistry.getInstance();
			registry.register(PingAction.class);
			registry.register(AddAction.class);
			registry.register(PutAction.class);
			registry.register(GetAction.class);
			registry.register(SaveAction.class);
			registry.register(ExitAction.class);
			registry.register(ShutdownAction.class);
			registry.register(DelAction.class);

			Server server = new Server(context, registry);

			server.run();
		} catch (Throwable e) {
			logger.error("Server Error!", e);
			System.exit(-1);
		}

	}
}
