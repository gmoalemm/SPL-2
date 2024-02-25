package bguspl.set.ex;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private final Dealer dealer;

    /** A lock that let the player thread sleep until a keypress occurres. */
    private Object keyPressLock;

    /**
     * A lock that let the player thread sleep until the dealer returns an answer.
     */
    protected Object playerTestLock;

    /** A queue of slots that the player chose to put tokens in. */
    private BlockingQueue<Integer> keyPreesesQueue;

    /**
     * Locks the queue such that when the player handles previous presses, new
     * presses cannot be added.
     */
    private Semaphore queueSemaphore;

    private Random rnd;

    /**
     * The answer from the dealer. -1 means the set was not legal, 1 means a legal
     * set, 0 means that the player was too slow.
     */
    protected int foundSet;

    public static final int NOT_LEGAL = -1;
    public static final int LEGAL_SET = 1;
    public static final int NEUTRAL = 0;

    private static final int BOT_BREAK_MILLIS = 500;

    /** Is this player waiting to be tested? */
    protected boolean waitingToBeTested;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.keyPressLock = new Object();
        this.playerTestLock = new Object();

        if (human)
            this.keyPreesesQueue = new LinkedBlockingQueue<Integer>();
        else
            this.keyPreesesQueue = new ArrayBlockingQueue<Integer>(3);

        this.queueSemaphore = new Semaphore(1, true);
        this.rnd = new Random();
        this.foundSet = NEUTRAL;
        this.waitingToBeTested = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        Integer slot, oldTokens;

        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            try {
                synchronized (this.keyPressLock) {
                    // wait for input manager to wake this thread up when a key press occures
                    this.keyPressLock.wait();
                }

                // gain control over the queue and handle the keypresses
                this.queueSemaphore.acquire();

                while (!this.keyPreesesQueue.isEmpty() && !this.waitingToBeTested) {
                    slot = this.keyPreesesQueue.poll();
                    oldTokens = this.table.tokensPerPlayer[id];

                    this.table.placeToken(this.id, slot);

                    // if the number of tokens changed from 2 to 3,
                    // this is the third token placement and we need to check if the cards form a
                    // legal set
                    if (oldTokens == Table.SET_SIZE - 1 && this.table.tokensPerPlayer[this.id] == Table.SET_SIZE) {
                        this.waitingToBeTested = true;
                        this.dealer.addPlayerToQueue(id);
                    }
                }

                if (this.waitingToBeTested) {
                    synchronized (this.playerTestLock) {
                        this.playerTestLock.wait();
                        this.waitingToBeTested = false;

                        if (this.foundSet == LEGAL_SET) {
                            this.point();
                        } else if (this.foundSet == NOT_LEGAL) {
                            this.penalty();
                        }

                        this.keyPreesesQueue.clear();
                    }
                }

                this.queueSemaphore.release();
            } catch (InterruptedException e) {

            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

            while (!terminate) {
                try {
                    int random = this.rnd.nextInt(this.env.config.tableSize);

                    while (this.table.slotToCard[random] == null) {
                        random = this.rnd.nextInt(this.env.config.tableSize);
                    }

                    this.keyPressed(random);

                    Thread.sleep(BOT_BREAK_MILLIS);
                } catch (InterruptedException ignored) {
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);

        aiThread.start();

    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
        this.playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!this.dealer.currentlyPlacingCards && this.queueSemaphore.tryAcquire()) {
            this.keyPreesesQueue.add(slot);
            this.queueSemaphore.release();
        }

        synchronized (this.keyPressLock) {
            this.keyPressLock.notify();
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        this.env.ui.setScore(id, ++score);
        this.env.ui.setFreeze(id, env.config.pointFreezeMillis);
        this.foundSet = Player.NEUTRAL;

        try {
            Thread.sleep(this.env.config.pointFreezeMillis);
        } catch (InterruptedException e) {
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        this.env.ui.setFreeze(this.id, this.env.config.penaltyFreezeMillis);
        this.foundSet = NEUTRAL;

        try {
            Thread.sleep(this.env.config.penaltyFreezeMillis);
        } catch (InterruptedException e) {
        }
    }

    public int score() {
        return score;
    }
}
