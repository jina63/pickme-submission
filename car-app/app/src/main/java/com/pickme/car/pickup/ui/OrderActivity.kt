package com.pickme.car.pickup.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.pickme.car.core.firebase.FirebaseRepo
import com.pickme.car.core.model.Order
import com.pickme.car.core.navi.NaviRemote
import com.pickme.car.core.navi.TripPoint
import com.pickme.car.pickup.domain.Candidate
import com.pickme.car.pickup.domain.OrderScheduler
import com.pickme.car.pickup.domain.StoreRecommender

/**
 * 딱픽업 화면. 한 Activity 안에서 네 가지 화면을 바꿔 보여 준다.
 * 메뉴 선택 → 계산 중 → 추천 결과 → 주문 진행
 */
class OrderActivity : Activity() {

    private enum class Screen { MENU, CALC, RESULT, PROGRESS }
    private enum class OrderStage { SCHEDULED, SENT, ACCEPTED, MAKING, READY, PICKED_UP }

    private val menus = listOf("돌체라떼", "아메리카노", "카페라떼")

    private var screen = Screen.MENU
    private var selectedMenu: String? = null
    private var pickedStore: Candidate? = null
    private var pickedTooFar = false                       // 우회 상한을 넘은 매장인가
    private var measured = false                           // 내비 계산값으로 추천했는가
    private var candidates: List<Candidate> = StoreRecommender.sampleCandidates()   // Firebase 읽기 전/실패 시 기본값
    private var estimating = false                         // 내비에 경로 시간을 묻는 중인가
    private var needRefresh = false                        // 계산 중에 경로가 또 바뀌었는가
    private var calcToken = 0                              // 오래된 계산 결과를 걸러 내는 번호
    private var calcSubtitle = SUB_CALC
    private var errorText: String? = null
    private var orderStarted = false                       // 확인을 누른 뒤에는 메뉴를 바꾸지 못하게 한다
    private var watchErrorText: String? = null              // 주문을 보낸 뒤 상태 감시가 실패했을 때 안내 문구
    private var orderStage = OrderStage.SCHEDULED
    private var planMessage = ""
    private val handler = Handler(Looper.getMainLooper())
    private var stopWatching: (() -> Unit)? = null         // 주문 상태 감시를 끄는 버튼

    // 경로가 바뀐 뒤 잠깐 기다렸다가 추천을 다시 계산한다
    private val refresh = Runnable {
        if (!orderStarted && screen == Screen.CALC && selectedMenu != null) startCalc(calcSubtitle)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ───────── 생명주기 ─────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NaviRemote.init(applicationContext)

        // 내비 목적지·경로가 바뀌면 오래된 추천으로 주문하지 못하게 막고 다시 계산한다
        NaviRemote.setRouteChangedListener {
            if (orderStarted || isDestroyed) return@setRouteChangedListener
            if (screen != Screen.RESULT && screen != Screen.CALC) return@setRouteChangedListener
            pickedStore = null
            calcSubtitle = SUB_CHANGED
            screen = Screen.CALC
            render()
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 1500)
        }

        // 매장 정보를 Firebase에서 읽는다. 실패하면 기본값(sampleCandidates)으로 계속 동작한다.
        FirebaseRepo.loadStores(
            onError = { Log.w("OrderActivity", "매장 읽기 실패, 기본값 사용: $it") }
        ) { stores ->
            runOnUiThread {
                val loaded = StoreRecommender.fromStores(stores)
                if (loaded.isNotEmpty()) candidates = loaded
                Log.d("OrderActivity", "Firebase 매장 ${loaded.size}곳 불러옴")
            }
        }

        render()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)            // 재계산만 취소. 예약된 주문 전송은 그대로 둔다
        stopWatching?.invoke()                      // 상태 감시 끄기
        NaviRemote.setRouteChangedListener(null)
        NaviRemote.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    // ───────── 화면 이동 ─────────

    private fun goBack() {
        when (screen) {
            Screen.MENU, Screen.PROGRESS -> finish()
            Screen.CALC, Screen.RESULT -> if (orderStarted) finish() else backToMenu()
        }
    }

    private fun backToMenu() {
        calcToken++                                  // 진행 중이던 계산 결과는 버린다
        handler.removeCallbacks(refresh)
        needRefresh = false
        pickedStore = null
        selectedMenu = null
        errorText = null
        screen = Screen.MENU
        render()
    }

    private fun render() {
        if (isDestroyed) return
        setContentView(buildScreen())
    }

    // ───────── 추천 계산 ─────────

    /** 내비에 경로 시간을 물어본 뒤, 가장 좋은 매장을 추천한다. */
    private fun startCalc(subtitle: String = SUB_CALC) {
        val menu = selectedMenu ?: return
        calcSubtitle = subtitle
        pickedStore = null
        errorText = null
        screen = Screen.CALC
        render()

        if (estimating) {
            needRefresh = true
            return
        }
        estimating = true
        val token = ++calcToken

        val points = candidates
            .filter { it.lat != 0.0 || it.lng != 0.0 }
            .map { TripPoint(it.storeId, it.lat, it.lng, it.name) }

        NaviRemote.estimateTrips(points) { snapshot ->
            estimating = false
            if (isFinishing || isDestroyed) return@estimateTrips
            if (needRefresh) {
                needRefresh = false
                if (screen == Screen.CALC) startCalc(calcSubtitle)
                return@estimateTrips
            }
            if (token != calcToken || screen != Screen.CALC) return@estimateTrips

            // 내비 계산이 되면 그 값으로, 안 되면 기존 고정값으로 추천한다
            val list = if (snapshot != null) StoreRecommender.withTrips(candidates, snapshot) else candidates
            Log.d(
                "OrderActivity",
                "추천 후보(${if (snapshot != null) "내비 계산값" else "기본값"}): " +
                        list.joinToString { "${it.storeId} 우회${it.detourMin}분 이동${it.travelMin}분 준비${it.prepMin}분" }
            )

            val result = StoreRecommender.recommend(list)
            if (result == null) {
                errorText = "추천할 매장이 없어요"
                screen = Screen.MENU
            } else {
                pickedStore = result.pick
                pickedTooFar = result.reason.contains("많이 돌아가요")
                measured = snapshot != null
                screen = Screen.RESULT
            }
            render()
        }
    }

    // ───────── 주문 ─────────

    /** 확인을 눌렀을 때: 주문 시점을 계산하고, 그 시점에 주문을 Firebase로 보낸다. */
    private fun onConfirm() {
        if (orderStarted) return                              // 이미 주문을 시작했으면 다시 만들지 않는다
        val store = pickedStore ?: return
        val menu = selectedMenu ?: return
        val plan = OrderScheduler.plan(store.travelMin, store.prepMin)

        orderStarted = true
        errorText = null
        watchErrorText = null
        handler.removeCallbacks(refresh)
        orderStage = OrderStage.SCHEDULED
        planMessage = "카페 경유지를 확인하고 있어요. 확인이 끝나야 주문이 예약돼요."
        screen = Screen.PROGRESS
        render()

        NaviRemote.addStoreStop(store.lat, store.lng, store.name) { error ->
            if (error != null) {
                showError(error)
                return@addStoreStop
            }
            val etaAt = FirebaseRepo.isoTime(plusMin = store.travelMin)
            planMessage = plan.message
            render()
            handler.postDelayed({
                FirebaseRepo.sendOrder(
                    Order(storeId = store.storeId, item = menu, qty = 1, etaAt = etaAt),
                    onDone = { id ->
                        runOnUiThread {
                            orderStage = OrderStage.SENT
                            render()
                        }
                        stopWatching?.invoke()
                        stopWatching = FirebaseRepo.watchOrder(id, onError = { showWatchError(it) }) { o ->
                            if (isDestroyed) {
                                stopWatching?.invoke()          // 화면을 나간 뒤에는 감시를 멈춘다
                            } else {
                                runOnUiThread {
                                    orderStage = stageOf(o.status.toString())
                                    render()
                                }
                            }
                        }
                    },
                    onError = { showError(it) },
                )
            }, OrderScheduler.delayMillis(plan))
        }
    }

    /** 주문이 아직 만들어지지 않은 오류일 때만 쓴다: 추천 화면으로 돌아가 알리고, 다시 시도할 수 있게 한다. */
    private fun showError(msg: Any?) = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        orderStarted = false
        errorText = "주문을 보내지 못했어요 ($msg)"
        screen = Screen.RESULT
        render()
    }

    /** 주문이 이미 만들어진 뒤의 오류: 진행 화면에 그대로 머물고 안내만 한다. (다시 주문하면 중복 주문이 된다) */
    private fun showWatchError(msg: Any?) = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        watchErrorText = "주문은 보냈지만 매장 상태를 받아 오지 못했어요 ($msg)"
        render()
    }

    /** 매장이 바꾸는 영어 상태값을 화면 단계로 바꾼다. */
    private fun stageOf(status: String): OrderStage = when (status) {
        "ordered" -> OrderStage.SENT
        "accepted" -> OrderStage.ACCEPTED
        "making" -> OrderStage.MAKING
        "ready" -> OrderStage.READY
        "picked_up" -> OrderStage.PICKED_UP
        else -> orderStage
    }

    // ───────── 화면 만들기 ─────────

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        when (screen) {
            Screen.MENU -> { buildMenu(content); buildMenuFooter(footer) }
            Screen.CALC -> { buildCalc(content); buildCalcFooter(footer) }
            Screen.RESULT -> { buildResult(content); buildResultFooter(footer) }
            Screen.PROGRESS -> { buildProgress(content); buildProgressFooter(footer) }
        }

        root.addView(buildHeader())
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )
        root.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(44))
        addView(
            tv("←  뒤로", 16f, Palette.GRAY).apply {
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { goBack() }
            },
            LinearLayout.LayoutParams(WRAP, dp(44))
        )
        addView(View(this@OrderActivity), LinearLayout.LayoutParams(0, 1, 1f))
        if (screen == Screen.RESULT) {
            addView(
                tv("메뉴 다시 고르기", 15f, Palette.ACCENT_TEXT, bold = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { backToMenu() }
                },
                LinearLayout.LayoutParams(WRAP, dp(44))
            )
        }
    }

    // 1) 메뉴 선택
    private fun buildMenu(content: LinearLayout) {
        content.addView(tv("어떤 음료를\n가져갈까요?", 30f, Palette.TEXT, bold = true).apply { setLineSpacing(0f, 1.15f) }, lp(top = 16))
        val sub = errorText ?: "가는 길에 들를 매장을 골라 드려요"
        content.addView(tv(sub, 17f, if (errorText != null) Palette.ORANGE else Palette.GRAY), lp(top = 12))

        // 2×2 박스. 메뉴가 3개라 마지막 칸은 자리만 둔다.
        val boxes = menus.map { menuBox(it) } + placeholderBox()
        boxes.chunked(2).forEachIndexed { row, pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, box ->
                line.addView(
                    box,
                    LinearLayout.LayoutParams(0, dp(176), 1f).apply {
                        if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
                    }
                )
            }
            content.addView(line, lp(top = if (row == 0) 28 else 12))
        }
    }

    private fun menuBox(menu: String): View {
        val selected = selectedMenu == menu
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(
                if (selected) Palette.SELECTED_BG else Palette.CARD, 24,
                if (selected) Palette.ACCENT else Color.TRANSPARENT, 2
            )
            isClickable = true
            setOnClickListener {
                selectedMenu = menu
                render()
            }
            addView(iconBadge(48, oval(Palette.ACCENT_BG), IconKind.CUP, Palette.ACCENT_TEXT, 26))
            addView(View(this@OrderActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(tv(menu, 20f, Palette.TEXT, bold = true))
        }
    }

    private fun placeholderBox(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = rounded(Palette.BG, 24, Palette.RING, 2, dashed = true)
        addView(iconBadge(48, oval(Palette.CARD), IconKind.PLUS, Palette.GRAY, 22))
        addView(View(this@OrderActivity), LinearLayout.LayoutParams(1, 0, 1f))
        addView(tv("메뉴 준비 중", 18f, Palette.GRAY, bold = true))
    }

    private fun buildMenuFooter(footer: LinearLayout) {
        if (selectedMenu == null) {
            footer.addView(ctaButton("메뉴를 골라 주세요", enabled = false) {})
        } else {
            footer.addView(ctaButton("매장 추천받기", enabled = true) { startCalc() })
        }
    }

    // 계산 중
    private fun buildCalc(content: LinearLayout) {
        content.addView(tv("경로를\n계산하고 있어요", 30f, Palette.TEXT, bold = true).apply { setLineSpacing(0f, 1.15f) }, lp(top = 16))
        content.addView(tv(calcSubtitle, 17f, Palette.GRAY), lp(top = 12))
        content.addView(
            ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(56) }
        )
    }

    private fun buildCalcFooter(footer: LinearLayout) {
        footer.addView(ctaButton("계산 중…", enabled = false) {})
    }

    // 2) 추천 결과
    private fun buildResult(content: LinearLayout) {
        val store = pickedStore ?: return
        val (title, place) = splitName(store.name)
        val menu = selectedMenu.orEmpty()

        content.addView(
            tv("추천 매장", 13f, Palette.ACCENT_TEXT, bold = true).apply {
                background = rounded(Palette.ACCENT_BG, 12)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) }
        )
        content.addView(tv(title, 22f, Palette.TEXT, bold = true), lp(top = 12))
        content.addView(tv(if (place.isNotBlank()) "$place · $menu" else menu, 15f, Palette.GRAY), lp(top = 6))
        if (pickedTooFar) {
            content.addView(tv("많이 돌아가는 경로예요", 14f, Palette.ORANGE, bold = true), lp(top = 6))
        }

        // 가장 중요한 정보: 기다림이 있는가
        val wait = store.waitMin
        if (wait > 0) {
            content.addView(iconBadge(64, oval(Palette.ORANGE_BG), IconKind.CLOCK, Palette.ORANGE, 32, topDp = 56))
        } else {
            content.addView(iconBadge(64, oval(Palette.ACCENT), IconKind.CHECK, Color.WHITE, 32, topDp = 56))
        }
        val hero = SpannableStringBuilder()
        if (wait > 0) {
            hero.append("도착 후\n")
            val start = hero.length
            hero.append("${wait}분")
            hero.setSpan(ForegroundColorSpan(Palette.ORANGE), start, hero.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            hero.append(" 기다려요")
        } else {
            hero.append("기다림 없이\n받아요")
        }
        content.addView(tv(hero, 40f, Palette.TEXT, bold = true).apply { setLineSpacing(0f, 1.15f) }, lp(top = 24))
    }

    private fun buildResultFooter(footer: LinearLayout) {
        val store = pickedStore ?: return

        footer.addView(View(this).apply { setBackgroundColor(Palette.LINE) }, LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(16) })

        // 부가 정보: 작게
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("우회" to store.detourMin, "이동" to store.travelMin, "준비" to store.prepMin).forEachIndexed { i, (label, min) ->
            val s = SpannableStringBuilder("$label ${min}분")
            s.setSpan(StyleSpan(Typeface.BOLD), label.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(Palette.TEXT), label.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            info.addView(tv(s, 15f, Palette.GRAY), LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(16) })
        }
        footer.addView(info, lp(top = 16))

        val note = errorText
            ?: if (measured) "내비 계산값 기준 · 목적지를 바꾸면 자동으로 다시 계산해요"
            else "내비 값을 가져오지 못해 기본값으로 계산했어요"
        footer.addView(tv(note, 13f, if (errorText != null) Palette.ORANGE else Palette.GRAY), lp(top = 12))

        footer.addView(
            ctaButton("이 매장에서 받을게요", enabled = true) { onConfirm() },
            LinearLayout.LayoutParams(MATCH, dp(60)).apply { topMargin = dp(16) }
        )
    }

    // 3) 주문 진행
    private fun buildProgress(content: LinearLayout) {
        val store = pickedStore
        val title = when (orderStage) {
            OrderStage.SCHEDULED -> "곧 주문을\n보낼게요"
            OrderStage.SENT -> "주문을\n보냈어요"
            OrderStage.ACCEPTED -> "매장이 주문을\n받았어요"
            OrderStage.MAKING -> "음료를\n만들고 있어요"
            OrderStage.READY -> "준비 완료!\n픽업하세요"
            OrderStage.PICKED_UP -> "픽업\n완료"
        }
        val doneCount = when (orderStage) {
            OrderStage.SCHEDULED -> 0
            OrderStage.SENT -> 1
            OrderStage.ACCEPTED -> 2
            OrderStage.MAKING -> 3
            OrderStage.READY, OrderStage.PICKED_UP -> 4
        }

        content.addView(tv(title, 30f, Palette.TEXT, bold = true).apply { setLineSpacing(0f, 1.15f) }, lp(top = 16))
        if (store != null) {
            content.addView(tv("${splitName(store.name).first} · ${selectedMenu.orEmpty()}", 17f, Palette.GRAY), lp(top = 12))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Palette.CARD, 20)
                setPadding(dp(24), dp(20), dp(24), dp(20))
            }
            card.addView(tv("도착 예정", 15f, Palette.GRAY), LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(tv("약 ${store.travelMin}분 뒤", 22f, Palette.TEXT, bold = true))
            content.addView(card, lp(top = 28))
        }
        content.addView(buildStepper(doneCount), lp(top = 36))
    }

    private fun buildStepper(doneCount: Int): View {
        val names = listOf("주문 전송", "매장 접수", "제조 중", "준비 완료")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.forEachIndexed { i, name ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            left.addView(stepCircle(i, doneCount))
            if (i < names.lastIndex) {
                left.addView(
                    View(this).apply { setBackgroundColor(if (i < doneCount) Palette.ACCENT else Palette.LINE) },
                    LinearLayout.LayoutParams(dp(2), dp(36))
                )
            }
            row.addView(left, LinearLayout.LayoutParams(dp(28), WRAP))
            row.addView(
                tv(name, 18f, if (i > doneCount) Palette.GRAY else Palette.TEXT, bold = (i == doneCount)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(WRAP, dp(28)).apply { marginStart = dp(16) }
            )
            box.addView(row)
        }
        return box
    }

    private fun stepCircle(index: Int, doneCount: Int): View {
        val size = LinearLayout.LayoutParams(dp(28), dp(28))
        return FrameLayout(this).apply {
            layoutParams = size
            when {
                index < doneCount -> {                       // 끝난 단계: 파란 원 + 체크
                    background = oval(Palette.ACCENT)
                    addView(IconView(this@OrderActivity, IconKind.CHECK, Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                }
                index == doneCount -> {                      // 지금 단계: 파란 테두리 + 점
                    background = oval(Color.WHITE, Palette.ACCENT, 3)
                    addView(View(this@OrderActivity).apply { background = oval(Palette.ACCENT) }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
                }
                else -> background = oval(Color.WHITE, Palette.RING, 2)   // 아직 안 한 단계
            }
        }
    }

    private fun buildProgressFooter(footer: LinearLayout) {
        val warn = watchErrorText
        val note = when {
            warn != null -> warn
            orderStage == OrderStage.SCHEDULED && planMessage.isNotBlank() -> planMessage
            else -> "다른 화면으로 가도 주문은 계속 진행돼요"
        }
        footer.addView(tv(note, 14f, if (warn != null) Palette.ORANGE else Palette.GRAY).apply { gravity = Gravity.CENTER }, lp(top = 8))
        footer.addView(
            ctaButton("홈으로", enabled = true, primary = false) { finish() },
            LinearLayout.LayoutParams(MATCH, dp(60)).apply { topMargin = dp(12) }
        )
    }

    // ───────── 작은 도구들 ─────────

    private fun tv(s: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0, dashed: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                if (dashed) setStroke(dp(strokeDp), strokeColor, dp(6).toFloat(), dp(5).toFloat())
                else setStroke(dp(strokeDp), strokeColor)
            }
        }

    private fun oval(color: Int, strokeColor: Int? = null, strokeDp: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    private fun iconBadge(sizeDp: Int, bg: GradientDrawable, kind: IconKind, color: Int, iconDp: Int, topDp: Int = 0): View =
        FrameLayout(this).apply {
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(topDp) }
            addView(IconView(this@OrderActivity, kind, color), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        }

    private fun ctaButton(label: String, enabled: Boolean, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        isEnabled = enabled
        setTextColor(
            when {
                !enabled -> Palette.DISABLED_TEXT
                primary -> Color.WHITE
                else -> Palette.SECONDARY_TEXT
            }
        )
        background = rounded(
            when {
                !enabled -> Palette.DISABLED_BG
                primary -> Palette.ACCENT
                else -> Palette.CARD
            },
            18
        )
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(60))
        setOnClickListener { onClick() }
    }

    /** "픽업카페 B (성남 판교)" → ("픽업카페 B", "성남 판교") */
    private fun splitName(name: String): Pair<String, String> {
        val m = Regex("^(.*?)\\s*\\((.*)\\)\\s*$").find(name) ?: return name to ""
        return m.groupValues[1] to m.groupValues[2]
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SUB_CALC = "내비에서 시간을 가져오는 중이에요"
        const val SUB_CHANGED = "내비 경로가 바뀌었어요. 다시 계산하고 있어요"
    }
}

/** 화면에 쓰는 색. */
private object Palette {
    val BG = Color.WHITE
    val TEXT = Color.parseColor("#191F28")
    val GRAY = Color.parseColor("#5B6573")
    val CARD = Color.parseColor("#F2F4F6")
    val LINE = Color.parseColor("#E5E8EB")
    val RING = Color.parseColor("#D1D6DB")
    val ACCENT = Color.parseColor("#4A6CE0")
    val ACCENT_TEXT = Color.parseColor("#3552C9")
    val ACCENT_BG = Color.parseColor("#E8EEFF")
    val SELECTED_BG = Color.parseColor("#F5F8FF")
    val ORANGE = Color.parseColor("#C2410C")
    val ORANGE_BG = Color.parseColor("#FFF1E6")
    val DISABLED_BG = Color.parseColor("#E5E8EB")
    val DISABLED_TEXT = Color.parseColor("#4E5968")
    val SECONDARY_TEXT = Color.parseColor("#333D4B")
}

private enum class IconKind { CUP, CHECK, CLOCK, PLUS }

/** 24×24 칸 기준으로 선 아이콘을 직접 그린다. (컵, 체크, 시계, 더하기) */
private class IconView(context: Context, private val kind: IconKind, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 24f
        canvas.save()
        canvas.scale(s, s)
        paint.color = color
        when (kind) {
            IconKind.CUP -> {
                paint.strokeWidth = 1.8f
                val body = Path().apply {
                    moveTo(5f, 9f); lineTo(16f, 9f); lineTo(16f, 14f)
                    quadTo(16f, 18f, 12f, 18f); lineTo(9f, 18f)
                    quadTo(5f, 18f, 5f, 14f); close()
                }
                canvas.drawPath(body, paint)
                val handle = Path().apply {
                    moveTo(16f, 10.5f); lineTo(17.5f, 10.5f)
                    arcTo(RectF(15.25f, 10.5f, 19.75f, 15f), -90f, 180f)
                    lineTo(16f, 15f)
                }
                canvas.drawPath(handle, paint)
                canvas.drawLine(8.5f, 3.5f, 8.5f, 5.5f, paint)
                canvas.drawLine(12f, 3.5f, 12f, 5.5f, paint)
            }
            IconKind.CHECK -> {
                paint.strokeWidth = 2.4f
                val check = Path().apply {
                    moveTo(5.25f, 12.75f); lineTo(9.75f, 17.25f); lineTo(18.75f, 6.75f)
                }
                canvas.drawPath(check, paint)
            }
            IconKind.CLOCK -> {
                paint.strokeWidth = 2f
                canvas.drawCircle(12f, 12f, 9f, paint)
                val hands = Path().apply {
                    moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f)
                }
                canvas.drawPath(hands, paint)
            }
            IconKind.PLUS -> {
                paint.strokeWidth = 2f
                canvas.drawLine(12f, 5f, 12f, 19f, paint)
                canvas.drawLine(5f, 12f, 19f, 12f, paint)
            }
        }
        canvas.restore()
    }
}