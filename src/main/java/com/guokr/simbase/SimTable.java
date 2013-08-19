package com.guokr.simbase;

import gnu.trove.iterator.TFloatIterator;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TFloatList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntFloatMap;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntFloatHashMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class SimTable implements KryoSerializable {

	public static SortedSet<Map.Entry<Integer, Float>> entriesSortedByValues(
			Map<Integer, Float> map) {
		SortedSet<Map.Entry<Integer, Float>> sortedEntries = new TreeSet<Map.Entry<Integer, Float>>(
				new Comparator<Map.Entry<Integer, Float>>() {
					@Override
					public int compare(Map.Entry<Integer, Float> e1,
							Map.Entry<Integer, Float> e2) {
						int sgn = (int) Math.signum(e2.getValue()
								- e1.getValue());
						if (sgn == 0) {
							sgn = (int) Math.signum(e2.hashCode()
									- e1.hashCode());
						}
						return sgn;
					}
				});
		synchronized (sortedEntries) {
			sortedEntries.addAll(map.entrySet());
		}
		return sortedEntries;
	}

	private static double LOADFACTOR;
	private static int MAXLIMITS;
	private static Map<String, Object> tableconfig;

	private TFloatList probs = new TFloatArrayList();
	private TIntIntMap indexer = new TIntIntHashMap();
	private TIntObjectHashMap<TIntList> reverseIndexer = new TIntObjectHashMap<TIntList>();
	private TIntFloatMap waterLine = new TIntFloatHashMap();
	private TIntObjectHashMap<SortedMap<Integer, Float>> scores = new TIntObjectHashMap<SortedMap<Integer, Float>>();

	public SimTable() {
		LOADFACTOR = 0.75;
		MAXLIMITS = 20;
	}

	public SimTable(Map<String, Object> config) {
		tableconfig = config;
		LOADFACTOR = Float.parseFloat((String) tableconfig.get("LOADFACTOR"));
		MAXLIMITS = Integer.parseInt((String) tableconfig.get("MAXLIMITS"));
	}

	private void setscore(int src, int tgt, float score) {
		SortedMap<Integer, Float> range = scores.get(src);
		if (range == null) {
			range = new TreeMap<Integer, Float>();
			synchronized (scores) {
				scores.put(src, range);
			}
		}

		TIntList reverseRange = reverseIndexer.get(tgt);
		if (reverseRange == null) {
			reverseRange = new TIntArrayList();
			reverseIndexer.put(tgt, reverseRange);
		}

		if (src != tgt) {
			if (waterLine.containsKey(src)) {
				if (waterLine.get(src) <= score) {// 先前的添加不改变水位线
					range.put(tgt, score);
					reverseRange.add(src);// 添加反向索引
				}
			} else {
				waterLine.put(src, 0f);
				range.put(tgt, score);
				reverseRange.add(src);// 添加反向索引
			}
		}
		if (range.size() > MAXLIMITS) {
			SortedSet<Map.Entry<Integer, Float>> entries = entriesSortedByValues(range);
			Map.Entry<Integer, Float> lastEntry = entries.last();
			range.remove(lastEntry.getKey());

			if (lastEntry.getValue() > waterLine.get(src))
				waterLine.put(src, lastEntry.getValue());// 放置水位线
			reverseRange.remove(src);
		}
	}

	public void add(int docid, float[] distr) {
		float length = 0;
		int start;
		if (indexer.containsKey(docid)) {
			start = indexer.get(docid);
			int cursor = start;
			for (float val : distr) {
				probs.set(cursor, val);
				length += val * val;
				cursor++;
			}
			probs.set(cursor++, (float) (docid + 1));
			probs.set(cursor, length);
		} else {
			start = probs.size();
			indexer.put(docid, start);
			for (float val : distr) {
				probs.add(val);
				length += val * val;
			}
			probs.add((float) (docid + 1));
			probs.add(length);
		}
		int end = probs.size();

		float score = 0;
		int base = 0;
		for (int offset = 0; offset < end; offset++) {
			float val = probs.get(offset);
			if (val >= 0) {
				if (val < 1) {
					int idx = offset - base;
					if (idx < end - start - 1) {
						float another = distr[idx];// ArrayIndexOutOfBoundsException
						score += another * val;
					}
				} else {
					float cosine = score * score / length
							/ probs.get(offset + 1);
					setscore(docid, (int) val - 1, cosine);
					setscore((int) val - 1, docid, cosine);
					score = 0;
					offset = offset + 1;
					base = offset + 1;
				}
			} else {
				base = offset + 1;

			}
		}
	}

	public void update(int docid, float[] distr) {
		add(docid, distr);
	}

	public void delete(int docid) {
		if (indexer.containsKey(docid)) {
			int cursor = indexer.get(docid);
			while (true) {
				float val = probs.get(cursor);
				if (val < 0f) {
					break;
				}
				if (val >= 1f) {// 到达docid的指针部分
					probs.set(cursor, -val);
					cursor++;
					val = probs.get(cursor);
					probs.set(cursor, -val);
					break;
				}

				probs.set(cursor, -val);
				cursor++;
			}
		}
		indexer.remove(docid);// HashMap里没有这个键了也可以用= =
		scores.remove(docid);
		// 根据反向索引移除scores
		TIntIterator reverseIter = reverseIndexer.get(docid).iterator();
		while (reverseIter.hasNext()) {
			int reverId = reverseIter.next();
			scores.get(reverId).remove(docid);
		}
		reverseIndexer.remove(docid);// 移除反向索引
		waterLine.remove(docid);// 移除水位线

	}

	public SortedSet<Map.Entry<Integer, Float>> retrieve(int docid) {
		if (scores.containsKey(docid)) {
			return entriesSortedByValues(scores.get(docid));
		} else {
			return entriesSortedByValues(new TreeMap<Integer, Float>());
		}
	}

	public float similarity(int docid1, int docid2) {
		return scores.get(docid1).get(docid2);
	}

	public SimTable clone() {
		SimTable peer = new SimTable(tableconfig);

		int cursor = 0, start = 0;
		TFloatIterator piter = this.probs.iterator();
		peer.probs = new TFloatArrayList((int) (this.probs.size() / LOADFACTOR));
		while (piter.hasNext()) {
			float value = piter.next();
			if (value < 0) {
				start++;
				// continue;
			} else {
				if (value > 1) {
					peer.indexer.put((int) value - 1, start);
					peer.probs.add(value);
					cursor++;
					peer.probs.add(piter.next());
					start = cursor + 1;
				} else {
					peer.probs.add(value);
				}
			}
			cursor++;
		}
		synchronized (this.scores) {
			TIntIterator siter = this.scores.keySet().iterator();
			while (siter.hasNext()) {
				Integer docid = siter.next();
				SortedMap<Integer, Float> thisscores = this.scores.get(docid);
				SortedMap<Integer, Float> peerscores = null;
				peerscores = new TreeMap<Integer, Float>();
				peer.scores.put(docid, peerscores);

				for (Integer key : thisscores.keySet()) {
					Float score = thisscores.get(key);
					peerscores.put(key, score);
				}
			}
		}

		return peer;
	}

	public void reload(SimTable table) {
		this.probs = table.probs;
		this.indexer = table.indexer;
		this.scores = table.scores;
	}

	@Override
	// 重载序列化代码
	public void read(Kryo kryo, Input input) {

		this.probs = kryo.readObject(input, TFloatArrayList.class);
		int indexsize = kryo.readObject(input, int.class);
		while (indexsize > 0) {
			int key = kryo.readObject(input, int.class);
			int value = kryo.readObject(input, int.class);
			this.indexer.put(key, value);
			indexsize--;
		}
		int scoresize = kryo.readObject(input, int.class);
		while (scoresize > 0) {
			Integer docid = kryo.readObject(input, Integer.class);
			SortedMap<Integer, Float> range = null;
			range = new TreeMap<Integer, Float>();
			this.scores.put(docid, range);
			int listsize = kryo.readObject(input, int.class);
			while (listsize > 0) {
				Integer key = kryo.readObject(input, Integer.class);
				Float score = kryo.readObject(input, Float.class);
				range.put(key, score);
				listsize--;
			}
			scoresize--;
		}
	}

	@Override
	public void write(Kryo kryo, Output output) {

		kryo.writeObject(output, this.probs);
		TIntIntMap indexmap = this.indexer;
		kryo.writeObject(output, indexmap.size());
		for (int key : indexmap.keys()) {
			int indexscore = indexmap.get(key);
			kryo.writeObject(output, key);
			kryo.writeObject(output, indexscore);
		}
		// kryo.writeObject(output, this.indexer);//hashmap不能直接放进去
		TIntIterator iter = this.scores.keySet().iterator();
		kryo.writeObject(output, this.scores.size());
		while (iter.hasNext()) {
			Integer docid = iter.next();
			kryo.writeObject(output, docid);
			SortedMap<Integer, Float> scoreList = scores.get(docid);
			kryo.writeObject(output, scoreList.size());
			for (Integer key : scoreList.keySet()) {
				Float score = scoreList.get(key);
				kryo.writeObject(output, key);
				kryo.writeObject(output, score);
			}
		}

	}
}
