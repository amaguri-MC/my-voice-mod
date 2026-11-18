package net.amaguri.myvoice.client.catalog;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

/** サウンドIDから日本語名・分類・アイコンを組み立てる。 */
final class SoundNames {
	private SoundNames() {}

	/** 動作語 -> 日本語（翻訳キー myvoice.action.<語>。fallback として使う）。 */
	static final Map<String, String> ACTIONS = new LinkedHashMap<>();
	/** ID の2番目の語 -> 日本語（翻訳キー myvoice.group.<語>）。ブロック等の登録名が無いものだけ。 */
	static final Map<String, String> GROUPS = new LinkedHashMap<>();
	/** ID の最初の語 -> 日本語（翻訳キー myvoice.prefix.<語>）。 */
	static final Map<String, String> PREFIXES = new LinkedHashMap<>();
	/** ブロックのアイコン用の別名（登録名が無い音の種類 -> 近いブロック）。 */
	private static final Map<String, String> BLOCK_ICON_ALIAS = new LinkedHashMap<>();

	private static void a(String k, String v) { ACTIONS.put(k, v); }
	private static void g(String k, String v, String iconBlock) {
		GROUPS.put(k, v);
		if (iconBlock != null) BLOCK_ICON_ALIAS.put(k, iconBlock);
	}

	static {
		a("ambient", "鳴く"); a("hurt", "ダメージ"); a("death", "倒れる"); a("step", "足音");
		a("break", "壊れる"); a("place", "置く"); a("hit", "たたく"); a("open", "開く");
		a("close", "閉じる"); a("attack", "攻撃"); a("eat", "食べる"); a("jump", "ジャンプ");
		a("splash", "水しぶき"); a("fall", "落下"); a("big_fall", "大きな落下"); a("small_fall", "小さな落下");
		a("fill", "入れる"); a("empty", "空にする"); a("explode", "爆発"); a("click", "クリック");
		a("pickup", "拾う"); a("throw", "投げる"); a("shoot", "撃つ"); a("swim", "泳ぐ");
		a("drink", "飲む"); a("burp", "ゲップ"); a("levelup", "レベルアップ"); a("equip", "装備する");
		a("use", "使う"); a("hurt_drown", "おぼれる"); a("hurt_on_fire", "燃える"); a("hurt_freeze", "凍える");
		a("attack_crit", "クリティカル攻撃"); a("attack_strong", "強攻撃"); a("attack_weak", "弱攻撃");
		a("attack_sweep", "なぎ払い"); a("attack_knockback", "ノックバック攻撃"); a("attack_nodamage", "空振り");
		a("flap", "羽ばたく"); a("lay_egg", "卵を産む"); a("hatch", "ふ化する"); a("shear", "毛を刈る");
		a("milk", "乳を搾る"); a("saddle", "サドルを付ける"); a("trade", "取引する"); a("yes", "うなずく");
		a("no", "首を振る"); a("celebrate", "喜ぶ"); a("fuse", "導火線"); a("ignite", "点火する");
		a("activate", "作動する"); a("deactivate", "停止する"); a("destroy", "破壊される");
		a("extinguish", "火が消える"); a("teleport", "テレポート"); a("growl", "うなる"); a("roar", "ほえる");
		a("bark", "吠える"); a("purr", "喉を鳴らす"); a("spit", "吐く"); a("screech", "叫ぶ");
		a("crack", "ひびが入る"); a("pop", "ポン"); a("loop", "環境音"); a("add_item", "アイテムを入れる");
		a("take_item", "アイテムを取る"); a("toggle", "切り替える"); a("rise", "上がる");
		a("flop", "跳ねる"); a("charge", "充填する"); a("land", "着地する"); a("spawn", "出現する");
		a("tame", "なつく"); a("sniff", "においを嗅ぐ"); a("dig", "掘る"); a("stare", "にらむ");

		g("generic", "全般", null); g("button", "ボタン", "stone_button"); g("armor", "防具", "iron_chestplate");
		g("cave", "洞窟", "stone"); g("metal", "金属", "iron_block"); g("wool", "羊毛", "white_wool");
		g("wood", "木材", "oak_planks"); g("nether_wood", "ネザーの木材", "crimson_planks");
		g("cherry_wood", "サクラの木材", "cherry_planks"); g("bamboo_wood", "竹の木材", "bamboo_planks");
		g("grass", "草", "grass_block"); g("wooden_door", "木のドア", "oak_door");
		g("wooden_trapdoor", "木のトラップドア", "oak_trapdoor"); g("wooden_button", "木のボタン", "oak_button");
		g("wooden_pressure_plate", "木の感圧板", "oak_pressure_plate");
		g("metal_pressure_plate", "金属の感圧板", "heavy_weighted_pressure_plate");

		PREFIXES.put("ambient", "環境音"); PREFIXES.put("weather", "天気"); PREFIXES.put("music", "音楽");
		PREFIXES.put("ui", "画面");
	}

	static SoundCatalog.Category categoryOf(Identifier id) {
		String p = id.getPath();
		if (p.startsWith("entity.player.") || p.equals("entity.player")) return SoundCatalog.Category.PLAYER;
		if (p.startsWith("entity.")) return SoundCatalog.Category.CREATURE;
		if (p.startsWith("block.")) return SoundCatalog.Category.BLOCK;
		if (p.startsWith("item.")) return SoundCatalog.Category.ITEM;
		if (p.startsWith("ambient.") || p.startsWith("weather.") || p.startsWith("music.")) return SoundCatalog.Category.AMBIENT;
		if (p.startsWith("ui.")) return SoundCatalog.Category.UI;
		return SoundCatalog.Category.OTHER;
	}

	private static Component tr(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args);
	}

	static Component pair(Component a, Component b) {
		return tr("myvoice.name.pair", "%s：%s", a, b);
	}

	static Component withSuffix(Component base, String suffix) {
		return tr("myvoice.name.suffix", "%s（%s）", base, suffix);
	}

	private static String humanize(String s) {
		return s.replace('_', ' ').replace('.', ' ');
	}

	private static <T> boolean has(Registry<T> r, Identifier id) {
		return r.containsKey(id);
	}

	private static Component subject(String prefix, Identifier id, String token) {
		String g = GROUPS.get(token);
		if (g != null) return tr("myvoice.group." + token, g);
		Identifier ref = Identifier.fromNamespaceAndPath(id.getNamespace(), token);
		boolean ent = prefix.equals("entity");
		boolean item = prefix.equals("item");
		if (ent && has(BuiltInRegistries.ENTITY_TYPE, ref)) return BuiltInRegistries.ENTITY_TYPE.getValue(ref).getDescription();
		if (!item && has(BuiltInRegistries.BLOCK, ref)) return BuiltInRegistries.BLOCK.getValue(ref).getName();
		if (has(BuiltInRegistries.ITEM, ref)) {
			Item it = BuiltInRegistries.ITEM.getValue(ref);
			return it.getName(it.getDefaultInstance());
		}
		if (item && has(BuiltInRegistries.BLOCK, ref)) return BuiltInRegistries.BLOCK.getValue(ref).getName();
		if (!ent && has(BuiltInRegistries.ENTITY_TYPE, ref)) return BuiltInRegistries.ENTITY_TYPE.getValue(ref).getDescription();
		return Component.literal(humanize(token));
	}

	private static Component action(String[] segs, int from) {
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < segs.length; i++) {
			if (i > from) sb.append('_');
			sb.append(segs[i]);
		}
		String key = sb.toString();
		String ja = ACTIONS.get(key);
		if (ja != null) return tr("myvoice.action." + key, ja);
		return Component.literal(humanize(key));
	}

	/** ID から組み立てた名前（字幕を使わない版）。 */
	static Component construct(Identifier id) {
		String[] segs = id.getPath().split("\\.");
		String prefix = segs[0];
		boolean ebi = prefix.equals("entity") || prefix.equals("block") || prefix.equals("item");
		if (segs.length == 1) return Component.literal(humanize(id.getPath()));
		if (segs.length == 2) {
			if (ebi) return subject(prefix, id, segs[1]);
			String pj = PREFIXES.get(prefix);
			Component p = pj != null ? tr("myvoice.prefix." + prefix, pj) : Component.literal(humanize(prefix));
			Component s = ebi ? subject(prefix, id, segs[1]) : (GROUPS.containsKey(segs[1])
					? tr("myvoice.group." + segs[1], GROUPS.get(segs[1])) : Component.literal(humanize(segs[1])));
			return pair(p, s);
		}
		return pair(subject(prefix, id, segs[1]), action(segs, 2));
	}

	static ItemStack icon(Identifier id, SoundCatalog.Category cat) {
		String[] segs = id.getPath().split("\\.");
		String token = segs.length > 1 ? segs[1] : "";
		Identifier ref = token.isEmpty() ? null : Identifier.fromNamespaceAndPath(id.getNamespace(), token);
		ItemStack s = ItemStack.EMPTY;
		try {
			switch (cat) {
				case PLAYER -> s = new ItemStack(Items.PLAYER_HEAD);
				case CREATURE -> {
					if (ref != null && has(BuiltInRegistries.ENTITY_TYPE, ref)) {
						EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(ref);
						Optional<Holder<Item>> egg = SpawnEggItem.byId(t);
						if (egg.isPresent()) s = new ItemStack(egg.get());
					}
					if (s.isEmpty()) s = new ItemStack(Items.PIG_SPAWN_EGG);
				}
				case BLOCK -> {
					s = blockItem(id, token);
					if (s.isEmpty()) s = new ItemStack(Items.STONE);
				}
				case ITEM -> {
					if (ref != null && has(BuiltInRegistries.ITEM, ref)) s = new ItemStack(BuiltInRegistries.ITEM.getValue(ref));
					if (s.isEmpty()) s = blockItem(id, token);
					if (s.isEmpty()) s = new ItemStack(Items.STICK);
				}
				case AMBIENT -> s = new ItemStack(Items.CLOCK);
				case UI -> s = new ItemStack(Items.LEVER);
				default -> s = new ItemStack(Items.NOTE_BLOCK);
			}
		} catch (RuntimeException e) {
			s = ItemStack.EMPTY;
		}
		return s.isEmpty() ? new ItemStack(Items.NOTE_BLOCK) : s;
	}

	private static ItemStack blockItem(Identifier id, String token) {
		if (token.isEmpty()) return ItemStack.EMPTY;
		String alias = BLOCK_ICON_ALIAS.get(token);
		Identifier ref = Identifier.fromNamespaceAndPath(alias != null ? "minecraft" : id.getNamespace(), alias != null ? alias : token);
		if (has(BuiltInRegistries.BLOCK, ref)) {
			Block b = BuiltInRegistries.BLOCK.getValue(ref);
			ItemStack st = new ItemStack(b);
			if (!st.isEmpty()) return st;
		}
		if (has(BuiltInRegistries.ITEM, ref)) return new ItemStack(BuiltInRegistries.ITEM.getValue(ref));
		return ItemStack.EMPTY;
	}

	/** 検索用: 全角半角・大文字小文字・カタカナ/ひらがなを揃える。 */
	static String fold(String s) {
		if (s == null) return "";
		String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder(n.length());
		for (int i = 0; i < n.length(); i++) {
			char c = n.charAt(i);
			if (c >= 'ァ' && c <= 'ヶ') c = (char) (c - 0x60);
			sb.append(c);
		}
		return sb.toString();
	}
}
