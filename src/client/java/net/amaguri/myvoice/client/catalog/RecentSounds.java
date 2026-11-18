package net.amaguri.myvoice.client.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

/** 「さっき鳴った音」。どのスレッドから record してもよい。 */
public final class RecentSounds {
	private RecentSounds() {}

	public record Heard(Identifier sound, long atMillis, double distance) {}

	private static final long MAX_AGE_MS = 60_000L;
	private static final int MAX_RESULTS = 50;
	private static final int MAX_STORED = 400;

	/** 先頭が最新。 */
	private static final ConcurrentLinkedDeque<Heard> HEARD = new ConcurrentLinkedDeque<>();

	/** SoundManagerMixin から全ての play で呼ぶ（置換された音も）。 */
	public static void record(SoundInstance s) {
		if (s == null) return;
		try {
			Identifier id = s.getIdentifier();
			if (id == null) return;
			double distance = 0;
			if (!s.isRelative()) {
				LocalPlayer p = Minecraft.getInstance().player;
				if (p != null) {
					double dx = s.getX() - p.getX();
					double dy = s.getY() - p.getY();
					double dz = s.getZ() - p.getZ();
					distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				}
			}
			long now = System.currentTimeMillis();
			HEARD.addFirst(new Heard(id, now, distance));
			prune(now);
		} catch (RuntimeException e) {
			// 記録の失敗で音の再生を止めない
		}
	}

	private static void prune(long now) {
		Heard last;
		while ((last = HEARD.peekLast()) != null && (now - last.atMillis() > MAX_AGE_MS || HEARD.size() > MAX_STORED)) {
			HEARD.pollLast();
		}
	}

	/** 新しい順、同じIDは最新1件、最大50件、60秒以内。 */
	public static List<Heard> recent() {
		long now = System.currentTimeMillis();
		prune(now);
		List<Heard> out = new ArrayList<>();
		Set<Identifier> seen = new HashSet<>();
		for (Heard h : HEARD) {
			if (now - h.atMillis() > MAX_AGE_MS) break;
			if (!seen.add(h.sound())) continue;
			out.add(h);
			if (out.size() >= MAX_RESULTS) break;
		}
		return out;
	}
}
