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

    /// synchronization

    private Object keyPressLock;

    protected Object playerTestLock;

    private BlockingQueue<Integer> keyPreesesQueue;

    private Semaphore queueSemaphore;

    private Random rnd;

    protected int foundSet; // -1 FAILED, 0 NEUTRAL, 1 SUCCEED

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
        this.foundSet = 0;
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

        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            try {
                synchronized (this.keyPressLock) {
                    // wait for input manager to wake this thread up when a key press occures
                    this.keyPressLock.wait();
                }

                // gain control over the queue
                this.queueSemaphore.acquire();

                while (!keyPreesesQueue.isEmpty()) {
                    Integer slot = keyPreesesQueue.poll();

                    if (!this.table.removeToken(this.id, slot) && this.table.tokensPerPlayer[this.id] < 3
                            && this.table.slotToCard[slot] != null) {
                        this.table.placeToken(this.id, slot);

                        if (this.table.tokensPerPlayer[this.id] == 3) {
                            this.waitingToBeTested = true;
                            this.table.waitingPlayers.add(this.id);
                        }
                    }
                }

                if (this.waitingToBeTested) {
                    synchronized (this.dealer.testLock) {
                        this.dealer.testLock.notify();
                    }

                    synchronized (this.playerTestLock) {
                        this.playerTestLock.wait();
                        this.waitingToBeTested = false;

                        if (this.foundSet == 1) {
                            this.point();
                        } else if (this.foundSet == -1) {
                            this.penalty();
                        }
                    }
                }

                this.queueSemaphore.release();

                if (!human) {
                    synchronized (this) {
                        notify();
                    }
                }
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

                    Thread.sleep(100);

                    while (!this.table.cardExists(random)) {
                        random = this.rnd.nextInt(this.env.config.tableSize);
                    }

                    this.keyPressed(random);

                    synchronized (this) {
                        wait();
                    }
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
        if (!this.dealer.placingCards && this.queueSemaphore.tryAcquire()) {
            this.keyPreesesQueue.add(slot);
            System.out.println("added slot " + slot);
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
        System.out.println("player " + this.id + " won");
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        this.env.ui.setFreeze(id, env.config.pointFreezeMillis);
        this.foundSet = 0;

        try {
            Thread.sleep(env.config.pointFreezeMillis);
        } catch (InterruptedException e) {
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        System.out.println("player " + this.id + " penalized");
        this.env.ui.setFreeze(id, env.config.penaltyFreezeMillis);
        this.foundSet = 0;
        try {
            Thread.sleep(env.config.penaltyFreezeMillis);
        } catch (InterruptedException e) {
        }
    }

    public int score() {
        return score;
    }
}
