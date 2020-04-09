package com.beastmode.playtq.livegame.view

import android.animation.*
import android.app.ProgressDialog
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.Observer
import com.beastmode.playtq.MyApplication
import com.beastmode.playtq.R
import com.beastmode.playtq.adapters.sections.GameChatItem
import com.beastmode.playtq.data.service.FirebaseService
import com.beastmode.playtq.databinding.LiveQuestionBinding
import com.beastmode.playtq.databinding.LiveQuestionCardBinding
import com.beastmode.playtq.databinding.RowAnswerBinding
import com.beastmode.playtq.interfaces.ChatPublishedListener
import com.beastmode.playtq.models.*
import com.beastmode.playtq.playanytime.model.PurchaseRetry
import com.beastmode.playtq.playanytime.views.ChatSection
import com.beastmode.playtq.utils.*
import com.beastmode.playtq.utils.constants.Constants
import com.beastmode.playtq.views.dialogs.GenericDialog
import com.beastmode.playtq.views.dialogs.SyncGameCallback
import com.beastmode.playtq.views.dialogs.SyncGameUseExtraLifeDialog
import com.beastmode.playtq.views.dialogs.UseExtraLifeDialog
import com.beastmode.playtq.views.fragments.DataPasser
import com.beastmode.playtq.wallet.views.ExtraLifeDialog
import com.google.gson.Gson
import com.squareup.picasso.Picasso
import com.tqtrivia.api.store.Store
import kotlinx.android.synthetic.main.fact_screen.view.*
import kotlinx.android.synthetic.main.live_question.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * Created by Olawale on 7/28/19.
 */
@ExperimentalCoroutinesApi
internal class LiveGame : ChatSection(), ChatPublishedListener {

    private lateinit var binding: LiveQuestionBinding
    private var messageText = ""
    private var currentUserScoreOnLeaderBoard: Leaderboard? = null
    private var attachment: NewsFeed? = null
    private var isKeyboardOpen = false
    private var learnMoreData: MutableList<Pair<Boolean, CurrentSynchronizeRoundData>> = mutableListOf()
    private lateinit var dataPasser: DataPasser

    private var qStartTime = 0L
    private var accessToPlayLiveGame = false
    private var extraLivesUsed = 0
    private var extraLivesAvailable = 0
    private var roundId: String = ""
    private var gameSchedule: GameSchedule? = null
    private var transactionProgressDialog: ProgressDialog? = null
    private var transactionCheckHandler: Handler? = null

    private val MAX_EXTRA_LIVES_ALLOWED = 3
    private var roundScore: Int = 0
    val handler by lazy {
        Handler()
    }
    private var windowWidth = 0
    private var userStore = UserStore()

    private var gameStateSequence: List<CurrentSynchronizeRoundData> = ArrayList()

    private fun buyIntoGame() {
        binding.join.setOnClickListener {
            if (accessToPlayLiveGame) return@setOnClickListener
            attemptToBuy()
        }
        gameViewShareModel.isSpecialGame().observe(viewLifecycleOwner, Observer { a ->
            if (!a.isNullOrBlank()) {
                gameViewModel.getSpecialGame(a).observe(viewLifecycleOwner, Observer { schedule ->
                    if (schedule != null) {
                        showGame(schedule)
                    }
                })
            } else {
                gameViewModel.queryLiveGame().observe(viewLifecycleOwner, Observer {
                    if (it != null) {
                        showGame(it)
                    }
                })
            }
        })
    }

    private fun showGame(it: GameSchedule?) {
        gameSchedule = it
        if (gameSchedule?.isGameLive == true) {
            setUpGameBoard()
            checkUserAccess()
        } else {
            showGameOver()
        }
    }

    private fun showGameOver() {
        activity?.supportFragmentManager?.popBackStack()
        val gameOver = GameOverScreen()
        gameOver.arguments = bundleOf(
                Constants.GAME_SCHEDULE to Gson().toJson(gameSchedule!!)
        )
        activity?.supportFragmentManager?.beginTransaction()
                ?.replace(R.id.main_host_fragment, gameOver, GameOverScreen.TAG)?.commit()

    }

    private fun checkUserAccess() {
        if (gameSchedule != null) {
            gameViewModel.checkUserAccess(Prefs.with(context!!).userId, gameSchedule?.newScheduleId!!)
                    .observe(this, Observer {
                        if (it != null) {
                            binding.joinGameLayout.visibility = GONE
                            dataPasser.showGameHeader(true)
                            accessToPlayLiveGame = true
                        } else {
                            binding.joinGameLayout.visibility = VISIBLE
                            dataPasser.showGameHeader(false)
                            accessToPlayLiveGame = false
                            attemptToBuy()
                        }
                    })
        }
    }

    private fun attemptToBuy() {
        LiveGamePurchase(context!!, gameSchedule!!, (synchronizeRound?.current?.index
                ?: 0) + 1) { access ->
            run {
                if (access) giveAccess()
            }
        }
    }

    private fun giveAccess() {
        binding.joinGameLayout.visibility = GONE
        accessToPlayLiveGame = true
        dataPasser.showGameHeader(true)
        showToast("Enjoy the game", binding.toast, binding.toastText)
        gameViewModel.grantGameAccess(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataPasser.showGameHeader(true)
        val dm = DisplayMetrics()
        activity?.windowManager?.defaultDisplay?.getMetrics(dm)
        windowWidth = dm.widthPixels
        gameViewModel.hasGameAccess().observe(this, Observer {
            accessToPlayLiveGame = it != null && it
        })
        walletViewModel.fetchUserStore(Prefs.with(context!!).userId).observe(this, Observer {
            if (it != null) {
                userStore = it
                extraLivesAvailable = userStore.lives.toInt()
            }
        })

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        binding = LiveQuestionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        dataPasser.showGameHeader(false)
        binding.mainBackground2.setMaxProgress(0.8f)
        binding.mainBackground2.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationRepeat(p0: Animator?) {
            }

            override fun onAnimationEnd(p0: Animator?) {
                dataPasser.showGameHeader(true)
                Common.fadeInView(binding.chatLayout)
                Common.fadeInView(round_to_round_layout)
                buyIntoGame()
            }

            override fun onAnimationCancel(p0: Animator?) {
            }

            override fun onAnimationStart(p0: Animator?) {
            }

        })
        setupChatSection()
    }

    private fun setupChatSection() {
        KeyboardVisibilityEvent.setEventListener(activity) { onKeyboardVisibilityChanged(it) }
        setupChatListView(binding.chatSection.chatListLayout, binding.chatSection.chatList)
        binding.chatSection.messageInput.setOnKeyListener { _, keyCode, event ->
            // If the event is a key-down event on the "enter" button
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                sendChat()
                true
            } else
                false
        }
        binding.chatSection.messageInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else
                false

        }
        binding.chatSection.openChat.setOnClickListener {
            startComposing()
        }

        binding.chatSection.chatLayout.setOnClickListener {
            startComposing()
        }
        binding.chatSection.chatList.setOnClickListener {
            if (isKeyboardOpen)
                stopComposing()
            else
                startComposing()
        }
        binding.chatSection.getExtraLife.setOnClickListener {
            ExtraLifeDialog(context!!)
        }
        setupChatListView(binding.chatSection.chatListLayout, binding.chatSection.chatList)
        setupMentions(binding.chatSection.messageInput, binding.chatSection.suggestionBox)
        listenForChatChanges()
    }

    private fun stopComposing() {
        if (adapter.itemCount > 0)
            linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
        binding.chatSection.suggestionBox.visibility = View.GONE
        decreaseChatLayoutHeight(binding.chatSection.chatLayout)
        binding.chatSection.messageInput.visibility = View.GONE
        binding.chatSection.openChat.visibility = View.VISIBLE
        binding.chatSection.getExtraLife.visibility = View.VISIBLE
        hideSoftKeyboard(binding.chatSection.messageInput, binding.chatSection.messageInput.context)
    }

    private fun onKeyboardVisibilityChanged(isOpen: Boolean) {
        isKeyboardOpen = isOpen
        if (!isKeyboardOpen) {
            stopComposing()
        }
    }

    private fun startComposing() {
        if (isKeyboardOpen) {
            stopComposing()
            return
        }
        increaseChatLayoutHeight(binding.chatSection.chatLayout)
        if (adapter.itemCount > 0)
            linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
        Common.fadeInView(binding.chatSection.messageInput)
        binding.chatSection.openChat.visibility = View.GONE
        binding.chatSection.getExtraLife.visibility = View.GONE
        showKeyboard(binding.chatSection.messageInput, binding.chatSection.chatList.context)
    }

    private fun listenForChatChanges() {
        gameViewModel.fetchRecentMessages().observe(viewLifecycleOwner, Observer {
            if (it != null) {
                setupRecents(it)
                gameViewModel.fetchLastLiveChat().observe(viewLifecycleOwner, Observer { a ->
                    if (a != null && !a.userId.equals(Prefs.with(context!!).userId)
                            && it.isNotEmpty() && it.last().timestamp != a.timestamp) {
                        updateLastChat(a)
                    }
                })
            }
        })
        gameViewModel.fetchStickyLiveChat().observe(viewLifecycleOwner, Observer {
            if (it != null) {
                binding.chatSection.stickyChatItem.visibility = VISIBLE
                binding.chatSection.stickyMessage.text = addClickableParts(it.name ?: "", it.text
                        ?: "")
                if (!it.photo.isNullOrBlank())
                    Picasso.get()
                            .load(it.photo)
                            .placeholder(R.drawable.ic_default_profile)
                            .error(R.drawable.ic_default_profile)
                            .into(binding.chatSection.stickyUserImage)
            }
        })
    }

    private fun setupRecents(comments: List<Comment>) {
        val groups = mutableListOf<GameChatItem>()
        for (comment in comments) {
            val gameChatItem = GameChatItem(context!!, comment) { action: Comment.Actions, c: Comment -> this.gameChatSelected(action, c) }
            groups.add(gameChatItem)
        }
        adapter.addAll(groups)
        linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
    }

    private fun gameChatSelected(action: Comment.Actions, conversation: Comment) {
        when (action) {
            Comment.Actions.SHOW_PROFILE -> {
                showProfile(conversation.userId, conversation.name)
            }
            Comment.Actions.OPEN_KEYBOARD -> {
                if (isKeyboardOpen)
                    stopComposing()
                else
                    startComposing()
            }
            else -> {
            }
        }
    }

    private fun sendChat() {
        messageText = binding.chatSection.messageInput.text!!.toString().trim()
        if (messageText.isNotEmpty()) {
            val comment = Comment()
            comment.userId = Prefs.with(context!!).userId
            comment.photo = Prefs.with(context!!).currentUserPhoto
            comment.name = Prefs.with(context!!).currentUserName
            comment.text = messageText
            comment.sticky = false
            comment.scheduleId = gameSchedule?.scheduleId
            comment.date = CalendarUtils.chatDateFormat
            if (suggestionAdapter != null)
                comment.mentionedUsers = suggestionAdapter!!.getValidMentions(messageText.split(" "))

            newsFeedViewModel.sendComment(Prefs.with(context!!).accessToken, comment, this)
            binding.chatSection.messageInput.setText("")
            updateLastChat(comment)
            try {
                val jsonObject = JSONObject()
                jsonObject.put("length", messageText.length)
                jsonObject.put("screen", "TQLite")
                MyApplication.instance.analyticsTracker?.logEvent("ChatSent", jsonObject)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateLastChat(comment: Comment) {
        messages.add(comment)
        suggestionAdapter?.addUsers(extractSuggestion(Collections.singletonList(comment)))
        val chat = GameChatItem(context!!, comment) { action: Comment.Actions, c: Comment -> this.gameChatSelected(action, c) }
        adapter.add(chat)
        linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
    }

    private fun setUpGameBoard() {
        if (gameSchedule != null) {
            FirebaseService().queryMyTopScore(gameSchedule?.scheduleId!!, Prefs.with(context!!).userId)
                    .get()
                    .addOnSuccessListener {
                        val leaderboard = it.toObject(Leaderboard::class.java)
                        if (leaderboard != null) {
                            currentUserScoreOnLeaderBoard = leaderboard
                            dataPasser.updateCurrentRoundScore(leaderboard.score)
                            roundScore = leaderboard.score
                            extraLivesUsed = leaderboard.lives
                        }
                    }

            transactionProgressDialog = ProgressDialog(context!!)
            transactionCheckHandler = Handler()

            activity?.volumeControlStream = AudioManager.STREAM_MUSIC
            setRoundId("")
            startNewRoundSession()
        }
    }

    private var countDownTimer: CountDownTimer? = null

    private val optionChoicesMap = HashMap<String, Any>()

    private fun createQuestionOptionView(qId: String, optionChoice: RoundQuestionOption, isResult: Boolean): View {
        val inflater = context?.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val rowAnswerBinding = RowAnswerBinding.inflate(inflater)
        rowAnswerBinding.optionText.text = optionChoice.option

        if (isResult) {
            val percentChange = optionChoice.percentage

            rowAnswerBinding.percentage.text = String.format("%.1f%%", percentChange * 100.0f)

            rowAnswerBinding.optionText.isEnabled = false
            rowAnswerBinding.optionText.gravity = Gravity.START or Gravity.CENTER_VERTICAL

            if (optionChoice.correct)
                rowAnswerBinding.percButton.background = ContextCompat.getDrawable(context!!, R.drawable.live_option_correct_bg)

            if (optionChoicesMap.containsKey(qId) && (optionChoicesMap[qId] == optionChoice.option && !optionChoice.correct))
                rowAnswerBinding.percButton.background = ContextCompat.getDrawable(context!!, R.drawable.live_option_wrong_bg)

            val constParam: ConstraintLayout.LayoutParams = rowAnswerBinding.percButton.layoutParams as ConstraintLayout.LayoutParams

            var percentWidth = optionChoice.percentage.toFloat()
            if (optionChoice.percentage < 0.1f && optionChoice.percentage > 0.0f)
                percentWidth = 0.1f

            val widthAnimator = ValueAnimator.ofFloat(0.0f, percentWidth)
            widthAnimator.duration = 2000
            widthAnimator.interpolator = DecelerateInterpolator()

            widthAnimator.addUpdateListener { animation ->
                constParam.matchConstraintPercentWidth = (animation.animatedValue as Float)
                rowAnswerBinding.percButton.layoutParams = constParam
            }
            widthAnimator.start()
        } else {
            rowAnswerBinding.percentage.text = ""
        }

        rowAnswerBinding.optionText.setOnClickListener {
            if (!accessToPlayLiveGame) {
                attemptToBuy()
                return@setOnClickListener
            }
            if (optionChoice.percentage > 0.0 || optionChoicesMap.containsKey(qId) || isResult) {
                return@setOnClickListener
            }
            optionChoicesMap[qId] = optionChoice.option!!
            val userId = Prefs.with(context!!).userId
            gameViewModel.submitOptionChoice(userId, gameSchedule?.scheduleId!!, qId, optionChoice.option!!, qStartTime)
            rowAnswerBinding.answer.background = ContextCompat.getDrawable(context!!, R.drawable.live_option_selected_bg)
            rowAnswerBinding.optionText.isEnabled = false
            rowAnswerBinding.optionText.setTextColor(ContextCompat.getColor(context!!, R.color.white))
            showToast("Answer locked in", binding.toast, binding.toastText, true)

            val props = JSONObject()
            props.put("screen", "SynchronizedLiveGame")
            props.put("roundId", roundId)
            props.put("option", optionChoice.option!!)
            props.put("action", "OptionChosen")
            props.put("gameType", "SynchronizedLiveGame")
            MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.LITE_OPTION_CHOSEN, props)
        }
        return rowAnswerBinding.root
    }

    private fun createQuestionCardView(currentRound: CurrentSynchronizeRoundData): View {
        val inflater = context?.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val questionCardBinding = LiveQuestionCardBinding.inflate(inflater)
        questionCardBinding.questionText.text = currentRound.question?.text
        questionCardBinding.qIndex.text = "${currentRound.index + 1}/${currentRound.total}"

        currentRound.question?.options?.forEach { option ->
            val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams.setMargins(0, 20, 0, 20)
            val optionView = createQuestionOptionView(currentRound.question?.qid!!, option, false)
            optionView.layoutParams = layoutParams
            questionCardBinding.questionOptionsList.addView(optionView)
        }

        if (countDownTimer != null) countDownTimer?.cancel()
        var duration = currentRound.endTime - System.currentTimeMillis()
        if (currentRound.duration + 2000 < duration) {
            duration = currentRound.duration
        }
        Timber.e("Durarion is %s %s", duration, Gson().toJson(currentRound))
        duration = currentRound.duration

        questionCardBinding.countdownAnim.setAnimation("game_question_countdown.json")
        questionCardBinding.countdownAnim.repeatCount = 0
        questionCardBinding.countdownAnim.playAnimation()
        questionCardBinding.countdownAnim.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationRepeat(p0: Animator?) {
            }

            override fun onAnimationEnd(p0: Animator?) {
                Handler().postDelayed({
                    if (!optionChoicesMap.containsKey(currentRound.question?.qid!!) || optionChoicesMap[currentRound.question?.qid!!] == "") {
                        SoundUtil.playWrongSound()
                        swipeCard(questionCardBinding.root, true, showFact = true)
                    } else
                        swipeCard(questionCardBinding.root, false, showFact = true)
                }, 1500)
            }

            override fun onAnimationCancel(p0: Animator?) {
            }

            override fun onAnimationStart(p0: Animator?) {
                if (duration < 0) {
                    questionCardBinding.countdownAnim.setMinProgress(0.9f)
                    return
                }
                if (p0 != null) {
                    questionCardBinding.countdownAnim.setMinProgress((p0.duration.minus(duration.plus(1000f))) / p0.duration)
                }
                qStartTime = System.currentTimeMillis()
                Handler().postDelayed({
                    if (!optionChoicesMap.containsKey(currentRound.question?.qid)) {
                        optionChoicesMap[currentRound.question?.qid!!] = ""

                        val userId = Prefs.with(context!!).userId
                        gameViewModel
                                .submitOptionChoice(userId, gameSchedule?.scheduleId!!, currentRound.question?.qid!!, "-", Common.nowDateTime)

                        val props = JSONObject()
                        props.put("screen", "SynchronizedLiveGame")
                        props.put("roundId", roundId)
                        props.put("action", "OptionChosen")
                        props.put("gameType", "SynchronizedLiveGame")
                        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.LITE_OPTION_TIMEOUT, props)
                    }
                    questionCardBinding.questionOptionsList.isEnabled = false
                }, duration)
            }

        })
        return questionCardBinding.root
    }

    private fun createQuestionAnswerCardView(currentRound: CurrentSynchronizeRoundData): View {
        val inflater = context?.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val questionCardBinding = LiveQuestionCardBinding.inflate(inflater)
        questionCardBinding.questionText.text = currentRound.question?.text
        questionCardBinding.qIndex.text = "${currentRound.index + 1}/${currentRound.total}"

        questionCardBinding.countdownAnim.visibility = View.GONE

        questionCardBinding.countdownAnim.setAnimation("tqlite_incorrect_answer.json")

        round_progress.text = "${currentRound.index + 2} OF ${currentRound.total}"

        var correct = false
        currentRound.answers.forEach { option ->
            if (optionChoicesMap.containsKey(currentRound.question?.qid) && (optionChoicesMap[currentRound.question?.qid] == option.option && option.correct)) {
                questionCardBinding.countdownAnim.setAnimation("tqlite_correct_answer.json")
                correct = true
                roundScore++
                dataPasser.updateCurrentRoundScore(roundScore)
            }

            val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams.setMargins(0, 20, 0, 20)
            val optionView = createQuestionOptionView(currentRound.question?.qid!!, option, true)
            optionView.layoutParams = layoutParams
            questionCardBinding.questionOptionsList.addView(optionView)
        }

        questionCardBinding.countdownAnim.visibility = View.VISIBLE

        questionCardBinding.countdownAnim.repeatCount = 0
        questionCardBinding.countdownAnim.speed = 0.5f

        //save for learn more
        if (learnMoreData.find { d -> d.second.index == currentRound.index } == null) {
            if (currentRound.fact?.attachment != null) {
                currentRound.fact?.attachment?.id = currentRound.fact?.attachment?.contentId
            }
            learnMoreData.add(Pair(correct, currentRound))
            sharedModel.setLearnMoreData(learnMoreData)
        }
        Handler().postDelayed({
            questionCardBinding.countdownAnim.playAnimation()
        }, 1000)

        questionCardBinding.questionOptionsList.isEnabled = false
        if (accessToPlayLiveGame) {
            if (optionChoicesMap.containsKey(currentRound.question?.qid) && !correct && currentRound.index + 1 < currentRound.total) {
                Handler().postDelayed({
                    SyncGameUseExtraLifeDialog(context!!, extraLivesAvailable, object : SyncGameCallback {
                        override fun onClose() {
                        }

                        override fun buyExtraLives() {
                            buyExtraLive()
                        }

                        override fun useExtraLives() {
                            if (extraLivesUsed >= MAX_EXTRA_LIVES_ALLOWED) {
                                maximumExtraLiveUsed()
                                return
                            }
                            attemptToUseExtraLive()
                            sendExtraLifeUsage()
                        }
                    }, roundId)
                }, 3000)
            }
        }
        return questionCardBinding.root
    }

    private fun setRoundId(roundId: String) {
        this.roundId = roundId
    }

    private fun setCurrentFact(fact: SynchronizeRoundFact?) {
        fact_screen.fact_text.visibility = View.GONE
        fact_screen.fact_image_view.visibility = View.GONE
        fact_screen.fact_video_view.visibility = View.GONE

        if (fact?.type.equals("text", true)) {
            fact_screen.fact_text.text = fact?.content ?: ""
            fact_screen.fact_text.visibility = View.VISIBLE
        }

        if (fact?.type.equals("image", true)) {
            fact_screen.fact_image_view.visibility = View.VISIBLE
            if (!fact?.link.isNullOrBlank())
                Picasso.get()
                        .load(fact?.link)
                        .resize(1000, 1448)
                        .onlyScaleDown()
                        .into(fact_screen.fact_image_view)
        }

        Timber.e("Fact is %s ", Gson().toJson(fact))
        attachment = fact?.attachment
        if (attachment != null && !attachment?.contentId.isNullOrBlank() && fact?.type.equals("newsfeed", true)) {
            fact_screen.related_news.visibility = View.VISIBLE
            fact_screen.source.text = attachment?.source?.name
            fact_screen.fact_text.visibility = View.VISIBLE
            fact_screen.fact_text.text = attachment?.caption
            if (!attachment?.source?.displayImage.isNullOrBlank())
                Picasso.get()
                        .load(attachment?.source?.displayImage)
                        .placeholder(R.drawable.ic_feed_default_source_image)
                        .error(R.drawable.ic_feed_default_source_image)
                        .into(fact_screen.source_image)
            if (!attachment?.displayImage.isNullOrBlank())
                Picasso.get()
                        .load(attachment!!.displayImage)
                        .placeholder(R.drawable.ic_feed_default_image)
                        .error(R.drawable.ic_feed_default_image)
                        .into(fact_screen.featured_image)
            fact_screen.caption.text = attachment?.caption
            if (Prefs.with(context!!).getCachePost.contains(attachment!!.contentId)) {
                fact_screen.save_later.text = "Saved"
            }
            fact_screen.related_news.setOnClickListener {
                saveForLater(attachment!!)
            }
        } else {
            fact_screen.fact_text.visibility = GONE
            fact_screen.related_news.visibility = GONE
        }
    }

    private fun saveForLater(newsFeed: NewsFeed) {
        if (Prefs.with(context!!).getCachePost.contains(newsFeed.contentId)) {
            fact_screen.save_later.text = "Save for later"
            Prefs.with(context!!).removeCachePost(newsFeed.id ?: "")
            playAnytimeInitiateViewModel.updateBookmark(Prefs.with(context!!).userId, newsFeed.contentId
                    ?: "", true)
            return
        }
        showToast("Saved, it would come up in your feed.", binding.toast, binding.toastText)
        fact_screen.save_later.text = "Saved"
        Prefs.with(context!!).cachePost(newsFeed.contentId ?: "")
        playAnytimeInitiateViewModel.updateBookmark(Prefs.with(context!!).userId, newsFeed.contentId
                ?: "", false)
    }

    private fun moveToFactScreen() {
        binding.questionCardStackView.visibility = View.GONE
        Common.fadeInView(fact_screen)
        dataPasser.showGameHeaderLogo(false)

        val duration = (synchronizeRound?.current!!.endTime + synchronizeRound?.current?.fact!!.duration) - System.currentTimeMillis()
        Timber.e("Time LEFT for FACT :: $duration")
        Handler().postDelayed({
            runSequenceState()
        }, 28000 + (bkpQIds * 1000))
    }

    private fun swipeCard(target: View, wiggle: Boolean, showFact: Boolean) {
        if (wiggle) {
            ObjectAnimator
                    .ofFloat(target, "translationX", 0f, 50f, -50f, 50f, -25f, 15f, -15f, 6f, -6f, 0f)
                    .setDuration(300)
                    .start()

            Handler().postDelayed({
                if (binding.questionCardStackView.childCount > 0) binding.questionCardStackView.removeAllViews()
                this.moveToFactScreen()
            }, 1000)
        } else {
            val rotation = ObjectAnimator.ofPropertyValuesHolder(
                    target, PropertyValuesHolder.ofFloat("rotation", -10f))
            rotation.duration = 500
            val translateX = ObjectAnimator.ofPropertyValuesHolder(
                    target, PropertyValuesHolder.ofFloat("translationX", 0f, -2000f))
            val translateY = ObjectAnimator.ofPropertyValuesHolder(
                    target, PropertyValuesHolder.ofFloat("translationY", 0f, 500f))
            translateX.startDelay = 100
            translateY.startDelay = 100
            translateX.duration = 1000
            translateY.duration = 1000
            val cardAnimationSet = AnimatorSet()
            cardAnimationSet.playTogether(rotation, translateX, translateY)

            cardAnimationSet.start()

            Handler().postDelayed({
                if (binding.questionCardStackView.childCount > 0) binding.questionCardStackView.removeAllViews()
                this.moveToFactScreen()
            }, 1000)
        }
    }

    private var synchronizeRound: SynchronizeRound? = null

    private var bkpQIds = 0L

    private fun runSequenceState() {
        if (synchronizeRound?.current!!.index < synchronizeRound?.current!!.total - 1 && gameStateSequence.isNotEmpty()) {
            if (synchronizeRound?.current!!.endTime < System.currentTimeMillis()) {
                synchronizeRound?.current = gameStateSequence[synchronizeRound?.current!!.index.toInt() + 1]
                synchronizeRound?.current?.endTime = System.currentTimeMillis() + 10000
                showQuestionState()
                Timber.e("Current BackUP Question is :: ${synchronizeRound?.current?.question!!.text}")
                bkpQIds++
            }
        }
    }

    private fun showQuestionState() {
        if (binding.questionCardStackView.childCount > 0) {
            binding.questionCardStackView.removeAllViews()
            binding.questionCardStackView.clearAnimation()
        }

        Common.fadeOutView(fact_screen)
        Common.fadeOutView(round_to_round_layout)

        dataPasser.showGameHeaderLogo(true)
        Common.fadeInView(binding.questionCardStackView)
        binding.questionCardStackView.addView(createQuestionCardView(synchronizeRound?.current!!))
        dataPasser.hideCircularRing(state = true, play = true)

        setCurrentFact(synchronizeRound?.current?.fact)

        val props = JSONObject()
        props.put("screen", "SynchronizedLiveGame")
        props.put("roundId", roundId)
        props.put("action", "ShowQuestion")
        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.LITE_QUESTION_VIEW, props)
    }

    private fun showFactState() {
        dataPasser.hideCircularRing(state = true, play = false)
        if (fact_screen.visibility != View.VISIBLE)
            fact_screen.visibility = View.VISIBLE

        val props = JSONObject()
        props.put("screen", "SynchronizedLiveGame")
        props.put("roundId", roundId)
        props.put("action", "ShowFact")
        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.LITE_FACT_VIEW, props)
    }

    private fun showQuestionAnswersState() {
        if (binding.questionCardStackView.childCount > 0) {
            binding.questionCardStackView.removeAllViews()
            binding.questionCardStackView.clearAnimation()
        }
        Common.fadeOutView(fact_screen)
        Common.fadeOutView(round_to_round_layout)
        Common.fadeInView(binding.questionCardStackView)

        Timber.e("Answers:: ${synchronizeRound?.current?.answers!!}")

        binding.questionCardStackView.addView(createQuestionAnswerCardView(synchronizeRound?.current!!))
        dataPasser.hideCircularRing(state = true, play = false)

        dataPasser.showGameHeaderLogo(false)

        if (countDownTimer != null) countDownTimer?.cancel()
        var duration = synchronizeRound?.current!!.endTime - System.currentTimeMillis()
        if (synchronizeRound?.current!!.duration + 2000 < duration) {
            duration = synchronizeRound?.current!!.duration
        }
        duration -= 3000L
        if (synchronizeRound?.current!!.index + 1 != synchronizeRound?.current!!.total) {
            countDownTimer = object : CountDownTimer(duration, 10) {

                override fun onTick(millisUntilFinished: Long) {
                    val seconds = (millisUntilFinished / 1000).toInt()

                    dataPasser.headerCountdown(seconds)

                    if (seconds < 1) {
                        Common.fadeInView(round_to_round_layout)
                        Common.fadeOutView(binding.questionCardStackView)
                        dataPasser.showGameHeaderLogo(true)

                        Handler().postDelayed({
                            runSequenceState()
                        }, 5000)
                    }
                }

                override fun onFinish() {
                }
            }.start()
        } else {
            dataPasser.showGameHeaderLogo(true)
        }

        val props = JSONObject()
        props.put("screen", "SynchronizedLiveGame")
        props.put("roundId", roundId)
        props.put("action", "ShowAnswer")
        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.LITE_ANSWER_VIEW, props)
    }

    private fun processCurrentLiveData(currentRound: SynchronizeRound?) {
        if (synchronizeRound != null && currentRound?.current!!.state == "SHOW_QUESTION" && currentRound.current!!.index == synchronizeRound?.current!!.index) {
            return
        }
        synchronizeRound = currentRound

        if (countDownTimer != null) countDownTimer?.cancel()
        if (currentRound?.current == null) return
        bkpQIds = 0L
        if (currentRound.current!!.state == "SHOW_QUESTION") {
            showQuestionState()
            Timber.e("Current *LIVE* Question is :: ${synchronizeRound?.current?.question!!.text}")
        }

        if (currentRound.current!!.state == "SHOW_FACT") {
            showFactState()
        }


        if (currentRound.current!!.state == "SHOW_ANSWERS") {
            showQuestionAnswersState()
        }
    }

    private fun startNewRoundSession() {
        val animation = AlphaAnimation(1f, 0f)
        animation.duration = 1500
        animation.interpolator = LinearInterpolator()
        animation.repeatCount = Animation.INFINITE
        animation.repeatMode = Animation.REVERSE
        round_to_round_layout.startAnimation(animation)

        dataPasser.hideCircularRing(state = true, play = false)

        gameViewModel.getGameStates(gameSchedule?.newScheduleId!!).observe(this, Observer { gameSequence ->
            if (gameSequence != null && gameSequence.isNotEmpty())
                gameStateSequence = gameSequence
        })
        gameViewModel.getSynchronizedRound(gameSchedule?.newScheduleId!!).observe(this, Observer { syncedRound ->
            if (syncedRound != null)
                processCurrentLiveData(syncedRound)
        })
    }

    private fun maximumExtraLiveUsed() {
        val genericDialog = GenericDialog(context!!, object : GenericDialog.GenericDialogCallback {
            override fun onButtonClick() {}
        })
        genericDialog.setTitle("No more extra lives allowed")
        genericDialog.setContent("You have used the maximum number of lives ($MAX_EXTRA_LIVES_ALLOWED) allowed in a game. You can keep playing without extra lives. Remember, everyone with highest score at the end of the game wins.")
        genericDialog.setButtonText("Continue")
        genericDialog.show()
    }

    private fun errorUsingExtraLive() {
        val props = JSONObject()
        props.put("screen", "SynchronizedLiveGame")
        props.put("roundId", roundId)
        props.put("result", "Auto use extra life use error, buy new lives")
        props.put("gameType", "TQLite")
        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.USE_EXTRA_LIFE_ERROR, props)
        //buyExtraLive()
    }

    private fun buyExtraLive() {
        val pricing = Gson().fromJson(Prefs.with(context!!).extraLivesPricing, WalletPricingItem::class.java)
        val store = Gson().fromJson(Prefs.with(context!!).store, UserStore::class.java)
        if (pricing.amount > store.coins) {
            showToast("Not enough coins. Please buy more coins before the game.", binding.toast, binding.toastText)
            return
        }
        extraLivesAvailable += pricing.quantity.toInt()
        dataPasser.updateExtraLivesCount(extraLivesAvailable.toString())
        //uodate user lives available
        attemptToUseExtraLive()
        store.coins -= pricing.amount
        //if (store.lives - 1 >= 0)
//            store.lives -= 1
//        else
//            store.lives = 0
//        //update store cache
        Prefs.with(context!!).store = Gson().toJson(store)
        walletViewModel.purchaseItem(Prefs.with(context!!).userId, pricing.quantity, WalletPricing.ItemType.EXTRA_LIFE)
                .observe(this, Observer {
                    if (it != null && it) {
                        Timber.e("Items purchased fine")
                        val props = JSONObject()
                        props.put("screen", "SynchronizedLiveGame")
                        props.put("roundId", roundId)
                        props.put("action", "Buy extra life")
                        props.put("gameType", "TQLite")
                        MyApplication.instance.analyticsTracker?.logEvent(TQAnalytics.BUY_EXTRA_LIFE, props)
                        sendExtraLifeUsage()
                    } else {
                        Timber.e("Items purchased Failed")
                        val coinRetry = PurchaseRetry()
                        coinRetry.type = PurchaseRetry.PurchaseRetryType.COINS_EXTRA_LIFE
                        coinRetry.quantity = pricing.quantity.toInt()
                        coinRetry.challengeId = gameSchedule?.scheduleId!!
                        coinRetry.questionId = synchronizeRound?.current?.question?.qid ?: ""
                        Prefs.with(context!!).addPurchaseRetry(coinRetry)
                    }
                })
        handler.postDelayed({ showToast("Purchased and Applied +1", binding.toast, binding.toastText) }, 1200)
    }

    private fun sendExtraLifeUsage() {
        Timber.e("Using extra life")
        try {
            gameViewModel.useExtraLife(Prefs.with(context!!).userId,
                    Store.UseLifeCall.GameType.TQ_LITE, gameSchedule?.scheduleId!!,
                    synchronizeRound?.current?.question?.qid ?: "")
                    .observe(this, Observer {
                        if (it != null && it.first) {
                            Timber.e("Life used fine")
                            this.extraLivesAvailable = it.second
                            val props = JSONObject()
                            props.put("screen", "SynchronizedLiveGame")
                            props.put("roundId", roundId)
                            props.put("action", "Continue round with extra life")
                            props.put("gameType", "TQLite")
                            MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.USE_EXTRA_LIFE, props)
                            //sharedModel.showPlus1Anim()
                        } else {
                            val coinRetry = PurchaseRetry()
                            coinRetry.type = PurchaseRetry.PurchaseRetryType.EXTRA_LIFE
                            coinRetry.challengeId = gameSchedule?.scheduleId!!
                            coinRetry.questionId = synchronizeRound?.current?.question?.qid ?: ""
                            Prefs.with(context!!).addPurchaseRetry(coinRetry)
                            errorUsingExtraLive()
                        }
                    })
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun attemptToUseExtraLive() {
        SoundUtil.playTapSound()
        if (extraLivesUsed >= MAX_EXTRA_LIVES_ALLOWED) {
            maximumExtraLiveUsed()
            return
        }
        this.extraLivesAvailable = --extraLivesAvailable
        dataPasser.updateExtraLivesCount(extraLivesAvailable.toString())
        val useExtraLifeDialog = UseExtraLifeDialog(context!!, object : UseExtraLifeDialog.UseExtraLifeDialogCallback {
            override fun onFinish() {
            }
        })
        try {
            useExtraLifeDialog.show()
            handler.postDelayed({
                useExtraLifeDialog.stopBeating()
                roundScore++
                extraLivesUsed++
                dataPasser.updateCurrentRoundScore(roundScore)
                sharedModel.showPlus1Anim()
            }, 800)
        } catch (e: Exception) {
            useExtraLifeDialog.stopBeating()
        }
    }


    private fun attemptToUseExtraLive1() {
        Log.e("continueWithExtraLife", "tapped!")
        val props = JSONObject()
        props.put("screen", "SynchronizedLiveGame")
        props.put("roundId", roundId)
        props.put("action", "Continue round with extra life")
        props.put("gameType", "TQLite")
        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.USE_EXTRA_LIFE, props)

        SoundUtil.playTapSound()
        if (extraLivesUsed >= MAX_EXTRA_LIVES_ALLOWED) {
            maximumExtraLiveUsed()
            return
        }
        val useExtraLifeDialog = UseExtraLifeDialog(context!!, object : UseExtraLifeDialog.UseExtraLifeDialogCallback {
            override fun onFinish() {
            }
        })
        useExtraLifeDialog.show()
        gameViewModel.useExtraLife(Prefs.with(context!!).userId,
                Store.UseLifeCall.GameType.TQ_LITE, gameSchedule?.scheduleId!!,
                synchronizeRound?.current?.question?.qid ?: "")
                .observe(this, Observer {
                    if (it != null && it.first) {
                        useExtraLifeDialog.stopBeating()
                        this.extraLivesAvailable = it.second
                        roundScore++
                        extraLivesUsed++
                        dataPasser.updateCurrentRoundScore(roundScore)
                        val props1 = JSONObject()
                        props1.put("screen", "SynchronizedLiveGame")
                        props1.put("roundId", roundId)
                        props1.put("action", "ExtraLifeUseSuccess")
                        props1.put("gameType", "TQLite")
                        MyApplication.instance.analyticsTracker!!.logEvent(TQAnalytics.USE_EXTRA_LIFE, props1)
                        sharedModel.showPlus1Anim()
                    } else {
                        useExtraLifeDialog.dismiss()
                        errorUsingExtraLive()
                    }
                })
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        dataPasser = context as DataPasser
    }

    companion object {
        val TAG = "LiveGame"
    }

}