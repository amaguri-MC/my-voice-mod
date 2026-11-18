package net.amaguri.myvoice.client.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.amaguri.myvoice.client.VoiceClientNet;
import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.audio.ClipPlayer;
import net.amaguri.myvoice.client.audio.MicRecorder;
import net.amaguri.myvoice.client.catalog.RecentSounds;
import net.amaguri.myvoice.client.catalog.SoundCatalog;
import net.amaguri.myvoice.net.VoiceNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * 声を割り当てる音を選ぶ画面。上: 題・検索・閉じる / 左: 分類 / 中央: 音の一覧 / 右: 詳細と録音。
 * 世界は止めない（isPauseScreen = false）。
 */
public final class SoundPickerScreen extends Screen {
	// ---- 色（細い罫線・文字中心。派手な色は使わない） ----
	private static final int WHITE = 0xFFFFFFFF;
	private static final int TEXT = 0xFFE6E6E6;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int DIM = 0xFF6E6E6E;
	private static final int LINE = 0x55FFFFFF;
	private static final int LINE_HOVER = 0xAAFFFFFF;
	private static final int ROW_SELECTED = 0x2EFFFFFF;
	private static final int ROW_HOVER = 0x14FFFFFF;
	/** 画面全体を覆うほぼ不透明の暗色（後ろのチャット・HUD が透けない）。 */
	private static final int BACKDROP = 0xE8101014;
	private static final int VOICE_MARK = 0xFF9CC9FF;
	private static final int ERROR = 0xFFFF5555;
	private static final int BAR_TRACK = 0x44FFFFFF;
	private static final int BAR_FILL = 0xFFE6E6E6;

	// ---- 寸法 ----
	private static final int HEAD_H = 30;
	private static final int ROW_H = 18;
	private static final int CAT_H = 14;
	private static final int SEPARATOR_GAP = 8;
	private static final int BTN_H = 14;
	private static final int SMALL_H = 12;
	/** バーが 1.0 に吸い付く幅（±）。 */
	private static final float SNAP = 0.03f;
	private static final int PREVIEW_TICKS = 6; // ドラッグ中の試聴間隔 0.3 秒
	private static final long SEND_DELAY_MS = 600; // 放してから送るまで
	private static final long SEND_MIN_GAP_MS = 1100; // サーバーの連投制限（1秒）より少し長く
	private static final int BAR_NONE = 0, BAR_LENGTH = 1, BAR_PITCH = 2, BAR_SPEED = 3, BAR_TRIM = 4, BAR_VOLUME = 5;
	private static final int WAVE_H = 14;
	private static final int CURTAIN = 0xB0101014; // 切られる範囲にかける半透明の暗幕
	private static final int PERSIST = Integer.MAX_VALUE;

	private record Cat(SoundCatalog.Category category, String key) {}

	/** 左列の並び。最後の「声つき」だけ区切り線の下。 */
	private static final Cat[] CATS = {
			new Cat(SoundCatalog.Category.RECENT, "myvoice.picker.cat.recent"),
			new Cat(SoundCatalog.Category.CREATURE, "myvoice.picker.cat.creature"),
			new Cat(SoundCatalog.Category.BLOCK, "myvoice.picker.cat.block"),
			new Cat(SoundCatalog.Category.PLAYER, "myvoice.picker.cat.player"),
			new Cat(SoundCatalog.Category.ITEM, "myvoice.picker.cat.item"),
			new Cat(SoundCatalog.Category.AMBIENT, "myvoice.picker.cat.ambient"),
			new Cat(SoundCatalog.Category.UI, "myvoice.picker.cat.ui"),
			new Cat(SoundCatalog.Category.ASSIGNED, "myvoice.picker.cat.assigned"),
	};
	private static final int SEPARATOR_BEFORE = CATS.length - 1;

	private record R(int x, int y, int w, int h) {
		boolean has(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	// ---- 状態 ----
	private EditBox search;
	private SoundCatalog.Category category = SoundCatalog.Category.RECENT;
	private String query = "";
	private List<SoundCatalog.Entry> rows = List.of();
	private final Map<Identifier, Long> heardAt = new HashMap<>();
	private int firstRow = 0;
	private Identifier selected = null;
	private SoundCatalog.Entry selectedEntry = null;
	private int seconds = RecordFlow.DEFAULT_SECONDS;
	/** ピッチ設定の表示。声があればその設定、無ければ次の録音に使う値。 */
	private boolean followLocal = false;
	/** 高さ・速さ。声があればその設定、無ければ次の録音に使う値。 */
	private float tunePitch = 1.0f;
	private float tuneSpeed = 1.0f;
	/** 無音カットの強さ（0〜1）。声があればその設定、無ければ次の録音に使う値。 */
	private float trimLocal = VoiceNet.DEFAULT_TRIM;
	/** 音量（0〜2、1 = そのまま、0 = 消音）。声があればその設定、無ければ次の録音に使う値。 */
	private float volumeLocal = 1.0f;
	/** 録り直しを押した後（長さバーを出す）。 */
	private boolean rerecord = false;
	private float[] envCache = null;
	private Identifier envFor = null;
	private int envWidth = 0;
	private int envTick = -100;
	/** setTune を送ってからサーバーの返事を待つ間（tick）。 */
	private int dragging = BAR_NONE;
	private int tickNo = 0;
	private int lastPreviewTick = -100;
	private boolean previewDirty = false;
	private boolean prevHadClip = false;
	/** まとめて送る予約（最後の値だけ）。sendTarget が null なら予約なし。 */
	private Identifier sendTarget = null;
	private long sendAtMs = 0;
	private static long lastSendMs = 0;
	private int refreshTicks = 0;
	private Component message = null;
	private int messageColor = TEXT;
	private int messageTicks = 0;
	private boolean backgroundDrawn = false;

	// ---- 配置（init / resize で計算） ----
	private int px, py, pw, ph;
	private int leftW, rightW;
	private int bodyTop, bodyBottom;
	private int searchX, searchY, searchW;
	private R closeBtn;

	// ---- 右列の部品（layoutDetail が毎回作り直す） ----
	private List<FormattedCharSequence> nameLines = List.of();
	private List<FormattedCharSequence> messageLines = List.of();
	private int detailX, detailW;
	private int nameY, idY, statusY, countY, barY, lengthY, pitchY, tuneY1, tuneY2, volY, trimY, clipInfoY, messageY;
	private R playOrig, playVoice, waveRect, trimBar, slider, pitchBar, speedBar, volumeBar, resetBtn, pitchVoice, pitchGame, recordBtn, stopBtn, clearBtn;

	private final RecordFlow.Listener flowListener = new RecordFlow.Listener() {
		@Override
		public void error(Component m) {
			if (isCurrent()) {
				showError(m);
			} else {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) mc.player.sendSystemMessage(m);
			}
		}

		@Override
		public void sent(Identifier sound) {
			if (isCurrent()) showInfo(Component.translatable("myvoice.picker.sent"));
		}
	};

	public SoundPickerScreen() {
		super(Component.translatable("myvoice.picker.title"));
	}

	private boolean isCurrent() {
		return Minecraft.getInstance().gui.screen() == this;
	}

	@Override
	public boolean isPauseScreen() {
		return false; // 世界を止めない: さっき鳴った音が更新され、録音中もゲームが動く
	}


	// ------------------------------------------------------------ 外部（テスト等）からの操作

	/** 分類を切り替える（左列のクリックと同じ）。 */
	public void openCategory(SoundCatalog.Category c) {
		category = c;
		refresh(true);
	}

	/** 検索欄の文字を入れ替える（入力と同じく一覧が絞り込まれる）。 */
	public void setQuery(String q) {
		search.setValue(q);
		query = q;
		refresh(true);
	}

	public String query() {
		return query;
	}

	/** 音を選ぶ（一覧にあればそこまでスクロール）。録音中は無視。 */
	public void choose(Identifier sound) {
		if (locked()) return;
		select(sound);
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).sound().equals(sound)) {
				if (i < firstRow || i >= firstRow + visibleRows()) firstRow = i;
				clampScroll();
				break;
			}
		}
	}

	public Identifier selectedSound() {
		return selected;
	}

	
	// ------------------------------------------------------------ 初期化・配置

	@Override
	protected void init() {
		computeLayout();
		search = new EditBox(this.font, searchX, searchY, searchW, 20, Component.translatable("myvoice.picker.search"));
		search.setMaxLength(40);
		search.setValue(query);
		search.setResponder(s -> {
			query = s;
			refresh(true);
		});
		addRenderableWidget(search);
		setInitialFocus(search);
		Identifier busyTarget = RecordFlow.target();
		if (busyTarget != null) select(busyTarget);
		refresh(false);
	}

	private void computeLayout() {
		int margin = 6;
		pw = Math.min(width - margin * 2, 460);
		ph = height - margin * 2;
		px = (width - pw) / 2;
		py = margin;
		int lw = 0;
		for (Cat c : CATS) lw = Math.max(lw, this.font.width(Component.translatable(c.key())));
		leftW = lw + 16;
		rightW = Math.max(132, Math.min(164, pw / 3));
		bodyTop = py + HEAD_H + 1;
		bodyBottom = py + ph;
		int closeW = this.font.width(Component.translatable("myvoice.picker.close")) + 12;
		closeBtn = new R(px + pw - 8 - closeW, py + (HEAD_H - BTN_H) / 2, closeW, BTN_H);
		searchW = Math.min(190, closeBtn.x() - 8 - (px + 8 + this.font.width(this.title) + 12));
		searchW = Math.max(80, searchW);
		searchX = closeBtn.x() - 6 - searchW;
		searchY = py + (HEAD_H - 20) / 2;
	}

	// ------------------------------------------------------------ 一覧

	private List<SoundCatalog.Entry> listFor(SoundCatalog.Category c, String q) {
		if (c == SoundCatalog.Category.AMBIENT) {
			// 「まわりの音」には分類 OTHER（イベント音など）も含める
			List<SoundCatalog.Entry> out = new ArrayList<>(SoundCatalog.list(SoundCatalog.Category.AMBIENT, q));
			out.addAll(SoundCatalog.list(SoundCatalog.Category.OTHER, q));
			return out;
		}
		return SoundCatalog.list(c, q);
	}

	private void refresh(boolean resetScroll) {
		rows = listFor(category, query);
		if (category == SoundCatalog.Category.RECENT) {
			heardAt.clear();
			for (RecentSounds.Heard h : RecentSounds.recent()) heardAt.put(h.sound(), h.atMillis());
		}
		if (resetScroll) firstRow = 0;
		clampScroll();
	}

	private int visibleRows() {
		return Math.max(1, (bodyBottom - (bodyTop + 2) - 2) / ROW_H);
	}

	private void clampScroll() {
		int max = Math.max(0, rows.size() - visibleRows());
		firstRow = Math.max(0, Math.min(max, firstRow));
	}

	private int listTop() {
		return bodyTop + 2;
	}

	private int centerLeft() {
		return px + leftW + 1;
	}

	private int centerRight() {
		return px + pw - rightW - 1;
	}

	private int catY(int i) {
		return bodyTop + 4 + i * CAT_H + (i >= SEPARATOR_BEFORE ? SEPARATOR_GAP : 0);
	}

	// ------------------------------------------------------------ 選択・状態

	private void select(Identifier sound) {
		if (sendTarget != null && !sendTarget.equals(sound)) sendNow();
		if (!java.util.Objects.equals(selected, sound)) rerecord = false;
		selected = sound;
		selectedEntry = sound == null ? null : SoundCatalog.entry(sound);
		clearMessage();
		ClientClips.ClipInfo clip = sound == null ? null : ClientClips.get(sound);
		followLocal = clip != null && clip.followPitch();
		VoiceClientNet.Tune pend = sound == null ? null : VoiceClientNet.pendingTune(sound);
		if (clip != null && pend != null) {
			followLocal = pend.followPitch();
			tunePitch = pend.pitch();
			tuneSpeed = pend.speed();
			trimLocal = pend.trim();
			volumeLocal = pend.volume();
		} else if (clip != null) {
			tunePitch = clip.pitch();
			tuneSpeed = clip.speed();
			trimLocal = clip.trim();
			volumeLocal = clip.volume();
		} else if (prevHadClip) {
			tunePitch = 1.0f;
			tuneSpeed = 1.0f;
			trimLocal = VoiceNet.DEFAULT_TRIM;
			volumeLocal = 1.0f;
		}
		prevHadClip = clip != null;
		previewDirty = false;
	}

	private ClientClips.ClipInfo clip() {
		return selected == null ? null : ClientClips.get(selected);
	}

	private void showInfo(Component m) {
		message = m;
		messageColor = TEXT;
		messageTicks = 70;
	}

	private void showError(Component m) {
		message = m;
		messageColor = ERROR;
		messageTicks = PERSIST;
	}

	private void clearMessage() {
		message = null;
		messageTicks = 0;
	}

	private boolean locked() {
		return RecordFlow.busy();
	}

	@Override
	public void tick() {
		super.tick();
		tickNo++;
		if (previewDirty && tickNo - lastPreviewTick >= PREVIEW_TICKS) previewNow();
		if (sendTarget != null && dragging == BAR_NONE && System.currentTimeMillis() >= sendAtMs) sendNow();
		if (++refreshTicks >= 20) {
			refreshTicks = 0;
			// 「さっき鳴った」は画面を開いている間も1秒ごとに更新（選択・スクロールは保つ）
			if (category == SoundCatalog.Category.RECENT || category == SoundCatalog.Category.ASSIGNED) refresh(false);
		}
		if (messageTicks > 0 && messageTicks != PERSIST && --messageTicks == 0) message = null;
		Identifier target = RecordFlow.target();
		if (target != null && !target.equals(selected)) select(target); // コマンドで録音が始まったとき
		// 声があるときは実際の設定に合わせる（断られても押した値のまま残さない）。
		// 自分が動かしている最中・送る予約中・送信後 1 秒ほどは合わせない
		if (dragging == BAR_NONE && sendTarget == null && !previewDirty && (selected == null || !VoiceClientNet.hasPendingTune(selected))) {
			ClientClips.ClipInfo clip = clip();
			if (clip != null) {
				followLocal = clip.followPitch();
				tunePitch = clip.pitch();
				tuneSpeed = clip.speed();
				trimLocal = clip.trim();
				volumeLocal = clip.volume();
			}
		}
	}

	/** 手元だけで、いまの値で試聴（送らない）。声が無ければ何もしない。 */
	private void previewNow() {
		previewDirty = false;
		lastPreviewTick = tickNo;
		if (selected != null && clip() != null) ClipPlayer.previewTuned(selected, tunePitch, tuneSpeed, trimLocal, volumeLocal);
	}

	/** 声があれば、最後の値だけを 0.6 秒後（連投制限を避けて）に送る予約。 */
	private void scheduleSend() {
		if (selected == null || clip() == null) return;
		sendTarget = selected;
		sendAtMs = Math.max(System.currentTimeMillis() + SEND_DELAY_MS, lastSendMs + SEND_MIN_GAP_MS);
	}

	private void sendNow() {
		Identifier target = sendTarget;
		sendTarget = null;
		if (target == null || ClientClips.get(target) == null) return;
		if (!RecordFlow.canSend()) {
			if (target.equals(selected)) showError(Component.translatable("myvoice.cmd.need_server"));
			return;
		}
		lastSendMs = System.currentTimeMillis();
		VoiceClientNet.queueTune(target, followLocal, tunePitch, tuneSpeed, trimLocal, volumeLocal);
	}

	@Override
	public void removed() {
		if (sendTarget != null) sendNow(); // 閉じるときに予約が残っていれば送る
		super.removed();
	}

	// ------------------------------------------------------------ 右列の配置

	private void layoutDetail() {
		playOrig = playVoice = waveRect = trimBar = slider = pitchBar = speedBar = volumeBar = resetBtn = pitchVoice = pitchGame = recordBtn = stopBtn = clearBtn = null;
		nameLines = List.of();
		messageLines = List.of();
		clipInfoY = 0;
		detailX = px + pw - rightW + 8;
		detailW = rightW - 16;
		int lh = this.font.lineHeight;
		if (message != null) {
			List<FormattedCharSequence> all = this.font.split(message, detailW);
			messageLines = all.size() > 3 ? all.subList(0, 3) : all;
		}
		int y = bodyTop + 6;
		if (selectedEntry != null) {
			List<FormattedCharSequence> all = this.font.split(selectedEntry.name(), detailW);
			int maxName = bodyBottom - bodyTop < 210 ? 1 : 2; // 最小の高さ（gui 240）では名前を1行にして収める
			nameLines = all.size() > maxName ? all.subList(0, maxName) : all;
			nameY = y;
			y += nameLines.size() * lh + 1;
			idY = y;
			y += 10 + 1;
			ClientClips.ClipInfo clip = clip();
			if (RecordFlow.busy()) {
				statusY = y;
				if (RecordFlow.countdownNumber() > 0) {
					y += lh + 4;
					countY = y;
					y += 3 * 8 + 6;
				} else {
					y += lh + 3;
					barY = y;
					y += 4 + 8;
				}
				stopBtn = new R(detailX, y, detailW, BTN_H);
				y += BTN_H + 5;
			} else {
				int half = (detailW - 4) / 2;
				playOrig = new R(detailX, y, half, BTN_H);
				playVoice = new R(detailX + half + 4, y, detailW - half - 4, BTN_H);
				y += BTN_H + 3;
				boolean showLength = clip == null || rerecord;
				if (clip != null) {
					waveRect = new R(detailX, y, detailW, WAVE_H);
					y += WAVE_H + 3;
				}
				trimY = y;
				y += lh;
				trimBar = new R(detailX, y, detailW, 9);
				y += 9 + 1;
				if (showLength) { // 長さは録るときだけ。ラベルと同じ行に置いて場所をとらない
					int lw = this.font.width(Component.translatable("myvoice.picker.length", "10")) + 4;
					lengthY = y + 1;
					slider = new R(detailX + lw, y, detailW - lw, 9);
					y += 9 + 1;
				}
				tuneY1 = y;
				y += lh;
				pitchBar = new R(detailX, y, detailW, 9);
				y += 9 + 1;
				tuneY2 = y;
				y += lh;
				speedBar = new R(detailX, y, detailW, 9);
				y += 9 + 1;
				volY = y;
				y += lh;
				volumeBar = new R(detailX, y, detailW, 9);
				y += 9 + 2;
				// 「元に戻す」と「消す」は同じ行（左右）
				int rw = this.font.width(Component.translatable("myvoice.tune.reset")) + 12;
				resetBtn = new R(detailX, y, rw, SMALL_H);
				if (clip != null) {
					int cw = this.font.width(Component.translatable("myvoice.picker.clear")) + 12;
					clearBtn = new R(detailX + detailW - cw, y, cw, SMALL_H);
				}
				y += SMALL_H + 3;
				// 「ピッチ」の見出しと切り替えを同じ行に
				pitchY = y + (SMALL_H - 8) / 2;
				int labelW = this.font.width(Component.translatable("myvoice.picker.pitch")) + 6;
				int segW = Math.max(20, detailW - labelW);
				int wl = Math.min(segW / 2,
						this.font.width(Component.translatable("myvoice.pitch.voice")) + 10);
				pitchVoice = new R(detailX + labelW, y, wl, SMALL_H);
				pitchGame = new R(detailX + labelW + wl, y, segW - wl, SMALL_H);
				y += SMALL_H + 3;
				recordBtn = new R(detailX, y, detailW, BTN_H);
				y += BTN_H + 3;
				if (clip != null && !showLength) { // 録り直し中は長さバーに場所を譲る
					clipInfoY = y;
					y += lh + 3;
				}
			}
		}
		int below = bodyBottom - 4 - messageLines.size() * lh;
		messageY = Math.max(y, below);
	}

	// ------------------------------------------------------------ 描画

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		backgroundDrawn = true;
		drawBackdrop(g);
	}

	private void drawBackdrop(GuiGraphicsExtractor g) {
		// HUD・チャットより上の層に描く（同じ層だとチャット等が透けて上に出る）
		g.fill(0, 0, width, height, BACKDROP);
		g.outline(px, py, pw, ph, LINE);
		g.fill(px, py + HEAD_H, px + pw, py + HEAD_H + 1, LINE); // 上の罫線
		g.fill(px + leftW, bodyTop, px + leftW + 1, bodyBottom, LINE); // 左列と中央の罫線
		g.fill(px + pw - rightW, bodyTop, px + pw - rightW + 1, bodyBottom, LINE); // 中央と右列の罫線
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (!backgroundDrawn) drawBackdrop(g); // 背景が別の経路で描かれなかった場合の保険
		backgroundDrawn = false;
		layoutDetail();

		// 上
		g.text(this.font, this.title, px + 8, py + (HEAD_H - 8) / 2, WHITE);
		search.extractRenderState(g, mouseX, mouseY, partialTick);
		if (search.getValue().isEmpty()) {
			g.text(this.font, Component.translatable("myvoice.picker.search_hint"), searchX + 5, searchY + 6, DIM, false);
		}
		drawButton(g, closeBtn, Component.translatable("myvoice.picker.close"), mouseX, mouseY, true);

		drawCategories(g, mouseX, mouseY);
		drawList(g, mouseX, mouseY);
		drawDetail(g, mouseX, mouseY);
	}

	private void drawButton(GuiGraphicsExtractor g, R r, Component label, int mx, int my, boolean enabled) {
		boolean hover = enabled && r.has(mx, my);
		if (hover) g.fill(r.x() + 1, r.y() + 1, r.x() + r.w() - 1, r.y() + r.h() - 1, ROW_HOVER);
		g.outline(r.x(), r.y(), r.w(), r.h(), hover ? LINE_HOVER : LINE);
		g.centeredText(this.font, label, r.x() + r.w() / 2, r.y() + (r.h() - 8) / 2, enabled ? WHITE : DIM);
	}

	private void drawCategories(GuiGraphicsExtractor g, int mx, int my) {
		for (int i = 0; i < CATS.length; i++) {
			Cat c = CATS[i];
			int y = catY(i);
			boolean sel = c.category() == category;
			boolean hover = mx >= px && mx < px + leftW && my >= y && my < y + CAT_H;
			if (hover && !sel) g.fill(px + 1, y, px + leftW, y + CAT_H, ROW_HOVER);
			if (sel) g.fill(px + 1, y + 1, px + 3, y + CAT_H - 1, WHITE); // 選択中は左に細い縦線
			g.text(this.font, Component.translatable(c.key()), px + 9, y + (CAT_H - 8) / 2, sel ? WHITE : GRAY, false);
			if (i == SEPARATOR_BEFORE) {
				int ly = y - SEPARATOR_GAP / 2;
				g.fill(px + 6, ly, px + leftW - 6, ly + 1, LINE); // 区切り線
			}
		}
	}

	private void drawList(GuiGraphicsExtractor g, int mx, int my) {
		int x0 = centerLeft();
		int x1 = centerRight();
		int top = listTop();
		int vis = visibleRows();
		boolean lock = locked();
		boolean hasBar = rows.size() > vis;
		int rightEdge = x1 - (hasBar ? 6 : 3);

		if (rows.isEmpty()) {
			Component hint = category == SoundCatalog.Category.RECENT && query.isBlank()
					? Component.translatable("myvoice.picker.empty_recent")
					: category == SoundCatalog.Category.ASSIGNED && query.isBlank()
							? Component.translatable("myvoice.picker.empty_assigned")
							: Component.translatable("myvoice.picker.empty_search");
			g.textWithWordWrap(this.font, hint, x0 + 10, top + 10, x1 - x0 - 20, DIM);
			return;
		}

		long now = System.currentTimeMillis();
		for (int i = 0; i < vis; i++) {
			int idx = firstRow + i;
			if (idx >= rows.size()) break;
			SoundCatalog.Entry e = rows.get(idx);
			int y = top + i * ROW_H;
			boolean sel = e.sound().equals(selected);
			boolean hover = !lock && mx >= x0 && mx < x1 && my >= y && my < y + ROW_H;
			if (sel) g.fill(x0, y, x1, y + ROW_H, ROW_SELECTED);
			else if (hover) g.fill(x0, y, x1, y + ROW_H, ROW_HOVER);
			g.item(e.icon(), x0 + 3, y + 1);

			// 右端の補足: 声の印と「n秒前」
			int rx = rightEdge;
			if (ClientClips.get(e.sound()) != null) {
				Component mark = Component.translatable("myvoice.picker.has_voice");
				int mw = this.font.width(mark);
				rx -= mw;
				g.text(this.font, mark, rx, y + (ROW_H - 8) / 2, lock ? DIM : VOICE_MARK, false);
				rx -= 5;
			}
			if (category == SoundCatalog.Category.RECENT) {
				Long at = heardAt.get(e.sound());
				if (at != null) {
					long s = Math.max(0, (now - at) / 1000);
					Component ago = s == 0 ? Component.translatable("myvoice.picker.just_now")
							: Component.translatable("myvoice.picker.seconds_ago", Long.toString(s));
					int aw = this.font.width(ago);
					rx -= aw;
					g.text(this.font, ago, rx, y + (ROW_H - 8) / 2, DIM, false);
					rx -= 5;
				}
			}

			int nameX = x0 + 3 + 16 + 5;
			String name = e.name().getString();
			int avail = rx - nameX;
			if (this.font.width(name) > avail) {
				String ell = "…";
				name = this.font.plainSubstrByWidth(name, Math.max(0, avail - this.font.width(ell))) + ell;
			}
			g.text(this.font, name, nameX, y + (ROW_H - 8) / 2, lock ? DIM : (sel ? WHITE : TEXT), false);
		}

		if (hasBar) { // 細いスクロールバー
			int trackTop = top;
			int trackH = vis * ROW_H;
			int barH = Math.max(10, trackH * vis / rows.size());
			int maxFirst = rows.size() - vis;
			int barY = trackTop + (maxFirst <= 0 ? 0 : (trackH - barH) * firstRow / maxFirst);
			g.fill(x1 - 3, trackTop, x1 - 2, trackTop + trackH, BAR_TRACK);
			g.fill(x1 - 4, barY, x1 - 1, barY + barH, 0x99FFFFFF);
		}
	}

	private void drawDetail(GuiGraphicsExtractor g, int mx, int my) {
		int lh = this.font.lineHeight;
		if (selectedEntry == null) {
			g.textWithWordWrap(this.font, Component.translatable("myvoice.picker.select_hint"),
					detailX, bodyTop + 10, detailW, DIM);
			drawMessage(g);
			return;
		}
		// 名前と ID
		int y = nameY;
		for (FormattedCharSequence line : nameLines) {
			g.text(this.font, line, detailX, y, WHITE);
			y += lh;
		}
		String idStr = selected.toString();
		int maxIdW = (int) (detailW / 0.75f);
		if (this.font.width(idStr) > maxIdW) {
			idStr = this.font.plainSubstrByWidth(idStr, maxIdW - this.font.width("…")) + "…";
		}
		g.pose().pushMatrix();
		g.pose().translate(detailX, idY);
		g.pose().scale(0.75f, 0.75f);
		g.text(this.font, idStr, 0, 0, GRAY, false);
		g.pose().popMatrix();

		if (RecordFlow.busy()) {
			drawRecordingPanel(g, mx, my);
		} else {
			drawButton(g, playOrig, Component.translatable("myvoice.picker.play_original"), mx, my, true);
			// 声が無くても押せる見た目のまま。押すと「まだ声がない」と出る
			drawButton(g, playVoice, Component.translatable("myvoice.picker.play_voice"), mx, my, true);

			if (waveRect != null) drawWave(g, waveRect);
			g.text(this.font, trimLabel(), detailX, trimY, TEXT, false);
			drawLinearBar(g, trimBar, trimLocal);
			if (slider != null) {
				g.text(this.font, Component.translatable("myvoice.picker.length", Integer.toString(seconds)), detailX, lengthY, TEXT, false);
				drawSlider(g);
			}

			g.text(this.font, Component.translatable("myvoice.tune.pitch", fmt(tunePitch)), detailX, tuneY1, TEXT, false);
			drawTuneBar(g, pitchBar, tunePitch);
			g.text(this.font, Component.translatable("myvoice.tune.speed", fmt(tuneSpeed)), detailX, tuneY2, TEXT, false);
			drawTuneBar(g, speedBar, tuneSpeed);
			g.text(this.font, volumeLabel(), detailX, volY, TEXT, false);
			drawVolumeBar(g, volumeBar, volumeLocal);
			drawButton(g, resetBtn, Component.translatable("myvoice.tune.reset"), mx, my, true);
			if (clip() != null) drawButton(g, clearBtn, Component.translatable("myvoice.picker.clear"), mx, my, true);

			g.text(this.font, Component.translatable("myvoice.picker.pitch"), detailX, pitchY, TEXT, false);
			drawSegment(g, pitchVoice, Component.translatable("myvoice.pitch.voice"), !followLocal, mx, my, true);
			drawSegment(g, pitchGame, Component.translatable("myvoice.pitch.game"), followLocal, mx, my, false);

			drawButton(g, recordBtn, Component.translatable(clip() != null && !rerecord ? "myvoice.picker.rerecord" : "myvoice.picker.record"), mx, my, true);

			ClientClips.ClipInfo clip = clip();
			if (clip != null && clipInfoY > 0) {
				g.text(this.font, Component.translatable("myvoice.picker.clip_info", clip.owner(),
						String.format("%.1f", clip.seconds())), detailX, clipInfoY, GRAY, false);
			}
		}
		drawMessage(g);
	}

	private void drawMessage(GuiGraphicsExtractor g) {
		int y = messageY;
		for (FormattedCharSequence line : messageLines) {
			g.text(this.font, line, detailX, y, messageColor, false);
			y += this.font.lineHeight;
		}
	}

	private void drawRecordingPanel(GuiGraphicsExtractor g, int mx, int my) {
		int cx = detailX + detailW / 2;
		int count = RecordFlow.countdownNumber();
		if (count > 0) {
			g.centeredText(this.font, Component.translatable("myvoice.hud.get_ready"), cx, statusY, TEXT);
			String s = Integer.toString(count);
			float scale = 3f;
			float x = cx - this.font.width(s) * scale / 2f;
			g.pose().pushMatrix();
			g.pose().translate(x, countY);
			g.pose().scale(scale, scale);
			g.text(this.font, s, 0, 0, WHITE);
			g.pose().popMatrix();
		} else {
			g.centeredText(this.font, Component.translatable("myvoice.hud.recording",
					String.format("%.1f", RecordFlow.remainingSeconds())), cx, statusY, WHITE);
			float level = Math.max(0f, Math.min(1f, MicRecorder.level()));
			g.fill(detailX, barY, detailX + detailW, barY + 3, BAR_TRACK);
			g.fill(detailX, barY, detailX + Math.round(detailW * level), barY + 3, BAR_FILL);
		}
		Component stopLabel = Component.translatable(count > 0 ? "myvoice.picker.cancel" : "myvoice.picker.stop");
		drawButton(g, stopBtn, stopLabel, mx, my, true);
	}

	private void drawSlider(GuiGraphicsExtractor g) {
		R s = slider;
		int trackY = s.y() + s.h() / 2;
		g.fill(s.x(), trackY, s.x() + s.w(), trackY + 1, LINE_HOVER);
		int max = VoiceNet.MAX_SECONDS;
		for (int i = 1; i <= max; i++) { // 1秒ごとの目盛り
			int tx = s.x() + 1 + (s.w() - 3) * (i - 1) / (max - 1);
			g.fill(tx + 1, trackY - 2, tx + 2, trackY + 3, LINE);
		}
		int hx = s.x() + (s.w() - 3) * (seconds - 1) / (max - 1);
		g.fill(hx, s.y(), hx + 3, s.y() + s.h(), WHITE);
	}

	/** 「無音カット 弱／中／強」。数値は出さない。 */
	private Component trimLabel() {
		String level = trimLocal < 0.2f ? "myvoice.trim.weak" : trimLocal < 0.6f ? "myvoice.trim.medium" : "myvoice.trim.strong";
		return Component.translatable("myvoice.trim.label", Component.translatable(level));
	}

	/** 「音量 100%」。0 のときは「音量 消音」。 */
	private Component volumeLabel() {
		if (volumeLocal <= 0f) return Component.translatable("myvoice.volume.mute");
		return Component.translatable("myvoice.volume.label", Math.round(volumeLocal * 100f) + "%");
	}

	/** 直線の 0〜200%。左端 = 消音、中央 = 100%（少し長い目盛り）。 */
	private void drawVolumeBar(GuiGraphicsExtractor g, R s, float v) {
		int trackY = s.y() + s.h() / 2;
		g.fill(s.x(), trackY, s.x() + s.w(), trackY + 1, LINE_HOVER);
		int span = s.w() - 3;
		g.fill(s.x() + 1, trackY - 2, s.x() + 2, trackY + 3, LINE); // 0%
		g.fill(s.x() + 1 + span, trackY - 2, s.x() + 2 + span, trackY + 3, LINE); // 200%
		int cx = s.x() + 1 + span / 2;
		g.fill(cx, trackY - 3, cx + 1, trackY + 4, LINE_HOVER); // 100%
		int hx = s.x() + Math.round(span * Math.max(0f, Math.min(1f, v / VoiceNet.MAX_VOLUME)));
		g.fill(hx, s.y(), hx + 3, s.y() + s.h(), WHITE);
	}

	/** 1% 刻み。100% の ±3% は 100% に吸着、ほぼ左端は消音（0）。 */
	private static float snapVolume(float v) {
		v = Math.round(VoiceNet.clampVolume(v) * 100f) / 100f;
		if (Math.abs(v - 1.0f) <= SNAP) return 1.0f;
		return v <= 0.01f ? 0f : v;
	}

	/** 長さバーと同じ作り（細い線＋つまみ）。左 0 = 切らない、右 1 = 強く切る。 */
	private void drawLinearBar(GuiGraphicsExtractor g, R s, float t) {
		int trackY = s.y() + s.h() / 2;
		g.fill(s.x(), trackY, s.x() + s.w(), trackY + 1, LINE_HOVER);
		int span = s.w() - 3;
		g.fill(s.x() + 1, trackY - 2, s.x() + 2, trackY + 3, LINE);
		g.fill(s.x() + 1 + span, trackY - 2, s.x() + 2 + span, trackY + 3, LINE);
		int hx = s.x() + Math.round(span * Math.max(0f, Math.min(1f, t)));
		g.fill(hx, s.y(), hx + 3, s.y() + s.h(), WHITE);
	}

	/** 波形の帯。残る範囲は明るく、切られる前後は暗幕、境目に細い縦線。trimLocal にその場で連動。 */
	private void drawWave(GuiGraphicsExtractor g, R r) {
		int inner = r.w() - 2;
		if (envCache == null || !selected.equals(envFor) || envWidth != inner || tickNo - envTick >= 20) {
			envCache = ClientClips.envelope(selected, inner);
			envFor = selected;
			envWidth = inner;
			envTick = tickNo;
		}
		g.outline(r.x(), r.y(), r.w(), r.h(), LINE);
		float[] env = envCache;
		if (env == null || env.length == 0) return;
		int mid = r.y() + r.h() / 2;
		int half = (r.h() - 2) / 2;
		int x0 = r.x() + 1;
		for (int i = 0; i < inner && i < env.length; i++) {
			int hh = Math.max(1, Math.round(Math.max(0f, Math.min(1f, env[i])) * half));
			g.fill(x0 + i, mid - hh, x0 + i + 1, mid + hh, TEXT);
		}
		float[] cut = ClientClips.cutFraction(selected, trimLocal);
		if (cut == null || cut.length < 2) return;
		int from = x0 + Math.round(inner * Math.max(0f, Math.min(1f, cut[0])));
		int to = x0 + Math.round(inner * Math.max(0f, Math.min(1f, cut[1])));
		if (to < from) to = from;
		if (from > x0) g.fill(x0, r.y() + 1, from, r.y() + r.h() - 1, CURTAIN);
		if (to < x0 + inner) g.fill(to, r.y() + 1, x0 + inner, r.y() + r.h() - 1, CURTAIN);
		int lineTo = Math.min(to, x0 + inner - 1);
		g.fill(from, r.y() + 1, from + 1, r.y() + r.h() - 1, WHITE);
		g.fill(lineTo, r.y() + 1, lineTo + 1, r.y() + r.h() - 1, WHITE);
	}

	private static String fmt(float v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	/** 長さバーと同じ作り（細い線＋つまみ）。左 0.5・中央 1.0・右 2.0 の対数目盛り。 */
	private void drawTuneBar(GuiGraphicsExtractor g, R s, float v) {
		int trackY = s.y() + s.h() / 2;
		g.fill(s.x(), trackY, s.x() + s.w(), trackY + 1, LINE_HOVER);
		int span = s.w() - 3;
		g.fill(s.x() + 1, trackY - 2, s.x() + 2, trackY + 3, LINE); // 0.5
		g.fill(s.x() + 1 + span, trackY - 2, s.x() + 2 + span, trackY + 3, LINE); // 2.0
		int cx = s.x() + 1 + span / 2;
		g.fill(cx, trackY - 3, cx + 1, trackY + 4, LINE_HOVER); // 1.0（少し長い目盛り）
		int hx = s.x() + Math.round(span * tuneToT(v));
		g.fill(hx, s.y(), hx + 3, s.y() + s.h(), WHITE);
	}

	private static float tuneToT(float v) {
		float c = VoiceNet.clampTune(v);
		return (float) (Math.log(c / VoiceNet.MIN_TUNE) / Math.log(VoiceNet.MAX_TUNE / VoiceNet.MIN_TUNE));
	}

	private static float tuneFromT(float t) {
		float v = (float) (VoiceNet.MIN_TUNE * Math.pow(VoiceNet.MAX_TUNE / VoiceNet.MIN_TUNE, Math.max(0f, Math.min(1f, t))));
		return snap(v);
	}

	private static float snap(float v) {
		v = VoiceNet.clampTune(v);
		return Math.abs(v - 1.0f) <= SNAP ? 1.0f : v;
	}

	private void drawSegment(GuiGraphicsExtractor g, R r, Component label, boolean on, int mx, int my, boolean first) {
		boolean hover = r.has(mx, my);
		if (on) g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), ROW_SELECTED);
		else if (hover) g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), ROW_HOVER);
		g.outline(r.x(), r.y(), r.w(), r.h(), on || hover ? LINE_HOVER : LINE);
		g.centeredText(this.font, label, r.x() + r.w() / 2, r.y() + (r.h() - 8) / 2, on ? WHITE : GRAY);
	}

	// ------------------------------------------------------------ 入力

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		if (search.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
		layoutDetail();

		if (closeBtn.has(mx, my)) {
			onClose();
			return true;
		}
		// 分類
		for (int i = 0; i < CATS.length; i++) {
			int y = catY(i);
			if (mx >= px && mx < px + leftW && my >= y && my < y + CAT_H) {
				if (category != CATS[i].category()) {
					category = CATS[i].category();
					refresh(true);
				}
				return true;
			}
		}
		// 一覧（録音中は行をクリックしても無視）
		if (mx >= centerLeft() && mx < centerRight() && my >= listTop() && my < listTop() + visibleRows() * ROW_H) {
			if (locked()) return true;
			int idx = firstRow + (int) ((my - listTop()) / ROW_H);
			if (idx >= 0 && idx < rows.size()) select(rows.get(idx).sound());
			return true;
		}
		// 右列
		if (selected != null && mx >= px + pw - rightW) {
			if (clickDetail(mx, my)) return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private boolean clickDetail(double mx, double my) {
		if (stopBtn != null && stopBtn.has(mx, my)) {
			RecordFlow.StopResult r = RecordFlow.stopEarly();
			if (r == RecordFlow.StopResult.COUNTDOWN_CANCELLED) showInfo(Component.translatable("myvoice.cmd.countdown_cancelled"));
			return true;
		}
		if (locked()) return false;
		if (playOrig != null && playOrig.has(mx, my)) {
			ClipPlayer.playOriginal(selected);
			return true;
		}
		if (playVoice != null && playVoice.has(mx, my)) {
			if (clip() == null || !ClipPlayer.preview(selected)) {
				showInfo(Component.translatable("myvoice.picker.no_voice"));
			}
			return true;
		}
		if (slider != null && grab(slider).has(mx, my)) {
			dragging = BAR_LENGTH;
			setSecondsFromMouse(mx);
			return true;
		}
		if (trimBar != null && grab(trimBar).has(mx, my)) {
			dragging = BAR_TRIM;
			setTrimFromMouse(mx);
			return true;
		}
		if (pitchBar != null && grab(pitchBar).has(mx, my)) {
			dragging = BAR_PITCH;
			setTuneFromMouse(mx);
			return true;
		}
		if (speedBar != null && grab(speedBar).has(mx, my)) {
			dragging = BAR_SPEED;
			setTuneFromMouse(mx);
			return true;
		}
		if (volumeBar != null && grab(volumeBar).has(mx, my)) {
			dragging = BAR_VOLUME;
			setVolumeFromMouse(mx);
			return true;
		}
		if (resetBtn != null && resetBtn.has(mx, my)) {
			resetTune();
			return true;
		}
		if (pitchVoice != null && pitchVoice.has(mx, my)) {
			setPitch(false);
			return true;
		}
		if (pitchGame != null && pitchGame.has(mx, my)) {
			setPitch(true);
			return true;
		}
		if (recordBtn != null && recordBtn.has(mx, my)) {
			if (clip() != null && !rerecord) {
				rerecord = true; // 録り直し: 長さバーを出す。もう一度押すと録音開始
				return true;
			}
			startRecord();
			return true;
		}
		if (clearBtn != null && clearBtn.has(mx, my)) {
			clearClip();
			return true;
		}
		return false;
	}

	private static R grab(R r) {
		return new R(r.x() - 2, r.y() - 2, r.w() + 4, r.h() + 4);
	}

	private void setPitch(boolean follow) {
		followLocal = follow;
		ClientClips.ClipInfo clip = clip();
		if (clip != null && clip.followPitch() != follow) { // 声が既にあれば切り替え（録音し直さない）。ほかの調整とまとめて送る
			if (!RecordFlow.canSend()) {
				showError(Component.translatable("myvoice.cmd.need_server"));
				return;
			}
			clearMessage();
			scheduleSend();
		}
	}

	private void resetTune() {
		if (tunePitch == 1.0f && tuneSpeed == 1.0f && volumeLocal == 1.0f) return;
		tunePitch = 1.0f;
		tuneSpeed = 1.0f;
		volumeLocal = 1.0f;
		tuneChanged(true);
	}

	/** 値が変わった: 試聴と（声があれば）送る予約。ボタンは now、ホイールは 0.3 秒間隔で試聴。 */
	private void tuneChanged(boolean now) {
		previewDirty = true;
		if (now) previewNow();
		scheduleSend();
	}

	private void setTuneFromMouse(double mx) {
		R s = dragging == BAR_PITCH ? pitchBar : dragging == BAR_SPEED ? speedBar : null;
		if (s == null) return;
		float t = (float) ((mx - s.x() - 1.5) / Math.max(1, s.w() - 3));
		float v = tuneFromT(t);
		if (dragging == BAR_PITCH) {
			if (v == tunePitch) return;
			tunePitch = v;
		} else {
			if (v == tuneSpeed) return;
			tuneSpeed = v;
		}
		previewDirty = true; // ドラッグ中は tick が 0.3 秒ごとに試聴
		if (selected != null && clip() != null) {
			sendTarget = selected; // 送るのは放して 0.6 秒後
			sendAtMs = Long.MAX_VALUE;
		}
	}

	private void setVolumeFromMouse(double mx) {
		R s = volumeBar;
		if (s == null) return;
		float t = (float) ((mx - s.x() - 1.5) / Math.max(1, s.w() - 3));
		float v = snapVolume(Math.max(0f, Math.min(1f, t)) * VoiceNet.MAX_VOLUME);
		if (v == volumeLocal) return;
		volumeLocal = v;
		previewDirty = true; // ドラッグ中は tick が 0.3 秒ごとに試聴
		if (selected != null && clip() != null) {
			sendTarget = selected; // 送るのは放して 0.6 秒後
			sendAtMs = Long.MAX_VALUE;
		}
	}

	private void setTrimFromMouse(double mx) {
		R s = trimBar;
		if (s == null) return;
		float t = (float) ((mx - s.x() - 1.5) / Math.max(1, s.w() - 3));
		float v = Math.round(VoiceNet.clampTrim(t) * 100f) / 100f;
		if (v == trimLocal) return;
		trimLocal = v; // 波形の暗幕は描画のたびに trimLocal から計算するのでその場で動く。試聴は放したとき
		if (selected != null && clip() != null) { // 送るのは放して 0.6 秒後
			sendTarget = selected;
			sendAtMs = Long.MAX_VALUE;
		}
	}

	private void startRecord() {
		if (selected == null || RecordFlow.busy()) return;
		if (!RecordFlow.canSend()) {
			showError(Component.translatable("myvoice.cmd.need_server"));
			return;
		}
		clearMessage();
		rerecord = false;
		RecordFlow.begin(selected, seconds, followLocal, tunePitch, tuneSpeed, trimLocal, volumeLocal, flowListener);
	}

	private void clearClip() {
		if (selected == null || clip() == null) return;
		if (!RecordFlow.canSend()) {
			showError(Component.translatable("myvoice.cmd.need_server"));
			return;
		}
		clearMessage();
		ClientPlayNetworking.send(new VoiceNet.ClearClipC2S(selected));
	}

	private void setSecondsFromMouse(double mx) {
		R s = slider;
		if (s == null) return;
		int max = VoiceNet.MAX_SECONDS;
		double t = (mx - s.x() - 1.5) / Math.max(1, s.w() - 3);
		int v = 1 + (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * (max - 1));
		seconds = Math.max(1, Math.min(max, v));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != BAR_NONE) {
			layoutDetail();
			if (!locked()) {
				if (dragging == BAR_LENGTH) setSecondsFromMouse(event.x());
				else if (dragging == BAR_TRIM) setTrimFromMouse(event.x());
				else if (dragging == BAR_VOLUME) setVolumeFromMouse(event.x());
				else setTuneFromMouse(event.x());
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		int was = dragging;
		dragging = BAR_NONE;
		if (was == BAR_PITCH || was == BAR_SPEED || was == BAR_TRIM || was == BAR_VOLUME) {
			previewNow(); // 放したとき必ず試聴
			if (sendTarget != null) {
				sendAtMs = Math.max(System.currentTimeMillis() + SEND_DELAY_MS, lastSendMs + SEND_MIN_GAP_MS);
			}
		}
		return was != BAR_NONE || super.mouseReleased(event);
	}

	/** ホイール 1 目盛り = 約 5%。1.0 をまたぐときは 1.0 で一度止まる。 */
	private static float wheel(float v, int dir) {
		float next = v * (dir > 0 ? 1.05f : 1 / 1.05f);
		if ((v < 1.0f && next > 1.0f) || (v > 1.0f && next < 1.0f)) next = 1.0f;
		return snap(next);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (scrollY != 0 && selected != null && !locked() && mx >= px + pw - rightW) {
			layoutDetail();
			int dir = (int) Math.signum(scrollY);
			if (slider != null && grab(slider).has(mx, my)) {
				seconds = Math.max(1, Math.min(VoiceNet.MAX_SECONDS, seconds + dir));
				return true;
			}
			if (trimBar != null && grab(trimBar).has(mx, my)) {
				trimLocal = Math.round(VoiceNet.clampTrim(trimLocal + dir * 0.05f) * 100f) / 100f;
				tuneChanged(false);
				return true;
			}
			if (pitchBar != null && grab(pitchBar).has(mx, my)) {
				tunePitch = wheel(tunePitch, dir);
				tuneChanged(false);
				return true;
			}
			if (speedBar != null && grab(speedBar).has(mx, my)) {
				tuneSpeed = wheel(tuneSpeed, dir);
				tuneChanged(false);
				return true;
			}
			if (volumeBar != null && grab(volumeBar).has(mx, my)) {
				float next = volumeLocal + dir * 0.05f;
				if ((volumeLocal < 1.0f && next > 1.0f) || (volumeLocal > 1.0f && next < 1.0f)) next = 1.0f; // 100% で一度止まる
				volumeLocal = snapVolume(next);
				tuneChanged(false);
				return true;
			}
		}
		if (mx >= centerLeft() && mx < centerRight() && my >= bodyTop && my < bodyBottom && scrollY != 0) {
			firstRow -= (int) Math.signum(scrollY) * 3;
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!locked() && (event.isUp() || event.isDown()) && !rows.isEmpty()) {
			int cur = -1;
			for (int i = 0; i < rows.size(); i++) {
				if (rows.get(i).sound().equals(selected)) {
					cur = i;
					break;
				}
			}
			int next = cur < 0 ? 0 : Math.max(0, Math.min(rows.size() - 1, cur + (event.isDown() ? 1 : -1)));
			select(rows.get(next).sound());
			if (next < firstRow) firstRow = next;
			if (next >= firstRow + visibleRows()) firstRow = next - visibleRows() + 1;
			clampScroll();
			return true;
		}
		return super.keyPressed(event);
	}
}
