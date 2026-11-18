package net.amaguri.myvoice.client.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.amaguri.myvoice.client.audio.ClientClips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** 音の目録。クライアントスレッドからのみ呼ぶ。初回 list() で作ってキャッシュする。 */
public final class SoundCatalog {
	private SoundCatalog() {}

	public enum Category { RECENT, CREATURE, BLOCK, PLAYER, ITEM, AMBIENT, UI, OTHER, ASSIGNED }

	public record Entry(Identifier sound, Component name, Category category, ItemStack icon) {}

	private static final class Cache {
		final Language language; // 言語変更・リソース再読込で差し替わる
		final int soundCount;
		final Map<Identifier, Entry> entries = new LinkedHashMap<>();
		final Map<Identifier, String> search = new HashMap<>();
		final Map<String, Integer> subtitleCounts = new HashMap<>();
		List<Entry> sorted = List.of();

		Cache(Language language, int soundCount) {
			this.language = language;
			this.soundCount = soundCount;
		}
	}

	private static Cache cache;

	/** 言語変更・リソース再読込で作り直す（次回の list/entry で再構築）。 */
	public static void invalidate() {
		cache = null;
	}

	public static List<Entry> list(Category c, String query) {
		Cache k = ensure();
		List<Entry> base = new ArrayList<>();
		if (c == Category.RECENT) {
			for (RecentSounds.Heard h : RecentSounds.recent()) base.add(entry(h.sound()));
		} else if (c == Category.ASSIGNED) {
			for (ClientClips.ClipInfo ci : ClientClips.all()) base.add(entry(ci.sound()));
			base.sort(BY_NAME);
		} else {
			for (Entry e : k.sorted) if (c == null || e.category() == c) base.add(e);
		}
		String q = SoundNames.fold(query == null ? "" : query.trim());
		if (q.isEmpty()) return base;
		String[] terms = q.split("\\s+");
		List<Entry> out = new ArrayList<>();
		for (Entry e : base) {
			String hay = searchText(k, e);
			boolean all = true;
			for (String t : terms) {
				if (!hay.contains(t)) { all = false; break; }
			}
			if (all) out.add(e);
		}
		return out;
	}

	/** 目録に無いID（鳴ったが未登録など）でも名前を組み立てて返す。 */
	public static Entry entry(Identifier sound) {
		Cache k = ensure();
		Entry e = k.entries.get(sound);
		if (e == null) {
			e = make(k, sound, subtitleOf(sound));
			k.entries.put(sound, e);
		}
		return e;
	}

	private static final Comparator<Entry> BY_NAME = Comparator
			.comparing((Entry e) -> SoundNames.fold(e.name().getString()))
			.thenComparing(e -> e.sound().toString());

	private static String searchText(Cache k, Entry e) {
		String s = k.search.get(e.sound());
		if (s == null) {
			s = SoundNames.fold(e.name().getString() + " " + e.sound());
			k.search.put(e.sound(), s);
		}
		return s;
	}

	private static Cache ensure() {
		Minecraft mc = Minecraft.getInstance();
		SoundManager sm = mc.getSoundManager();
		Language lang = Language.getInstance();
		int count = sm == null ? 0 : sm.getAvailableSounds().size();
		Cache k = cache;
		if (k != null && k.language == lang && k.soundCount == count) return k;
		k = build(lang, sm, count);
		cache = k;
		return k;
	}

	private static String subtitleOf(Identifier id) {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		if (sm == null) return null;
		try {
			WeighedSoundEvents w = sm.getSoundEvent(id);
			if (w == null) return null;
			Component sub = w.getSubtitle();
			if (sub == null) return null;
			String s = sub.getString();
			// 翻訳が無いと生のキーが出るので捨てる
			if (s.isBlank() || s.startsWith("subtitles.")) return null;
			return s;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Cache build(Language lang, SoundManager sm, int count) {
		Cache k = new Cache(lang, count);
		Set<Identifier> ids = new HashSet<>(BuiltInRegistries.SOUND_EVENT.keySet());
		if (sm != null) ids.addAll(sm.getAvailableSounds());
		ids.remove(SoundManager.EMPTY_SOUND_LOCATION);
		ids.remove(SoundManager.INTENTIONALLY_EMPTY_SOUND_LOCATION);

		Map<Identifier, String> subs = new HashMap<>();
		for (Identifier id : ids) {
			String s = subtitleOf(id);
			if (s != null) {
				subs.put(id, s);
				k.subtitleCounts.merge(s, 1, Integer::sum);
			}
		}
		List<Entry> all = new ArrayList<>(ids.size());
		for (Identifier id : ids) all.add(make(k, id, subs.get(id)));

		// 同名が並ぶものには ID の最後の部分などを補足する
		Map<String, List<Entry>> byName = new HashMap<>();
		for (Entry e : all) byName.computeIfAbsent(e.name().getString(), x -> new ArrayList<>()).add(e);
		List<Entry> finalList = new ArrayList<>(all.size());
		for (List<Entry> group : byName.values()) {
			if (group.size() == 1) { finalList.addAll(group); continue; }
			finalList.addAll(disambiguate(group));
		}
		finalList.sort(BY_NAME);
		for (Entry e : finalList) k.entries.put(e.sound(), e);
		k.sorted = finalList;
		return k;
	}

	private static List<Entry> disambiguate(List<Entry> group) {
		String[] modes = { "last", "tail", "full" };
		List<Entry> next = group;
		for (String mode : modes) {
			next = new ArrayList<>(group.size());
			Map<String, Integer> seen = new HashMap<>();
			for (Entry e : group) {
				Component n = SoundNames.withSuffix(e.name(), suffix(e.sound(), mode));
				next.add(new Entry(e.sound(), n, e.category(), e.icon()));
				seen.merge(n.getString(), 1, Integer::sum);
			}
			if (seen.values().stream().allMatch(v -> v == 1)) break;
		}
		return next;
	}

	private static String suffix(Identifier id, String mode) {
		String p = id.getPath();
		String[] segs = p.split("\\.");
		return switch (mode) {
			case "last" -> segs[segs.length - 1];
			case "tail" -> segs.length > 1 ? p.substring(p.indexOf('.') + 1) : p;
			default -> id.toString();
		};
	}

	private static Entry make(Cache k, Identifier id, String subtitle) {
		Category cat = SoundNames.categoryOf(id);
		Component name;
		if (subtitle != null && k.subtitleCounts.getOrDefault(subtitle, 0) <= 1) {
			name = Component.literal(subtitle);
		} else {
			name = SoundNames.construct(id);
		}
		return new Entry(id, name, cat, SoundNames.icon(id, cat));
	}
}
