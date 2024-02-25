package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /** An array of players. */
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * A lock that's used to let the dealer sleep as long as there is no work to do.
     */
    public Object dealerSleepLock;

    /**
     * An array of points in time, each corresponds to a different player, and
     * represents the time they should be unfrozen.
     * 
     * @note The default value is 0 and it indicates that the player is not frozen.
     */
    private long[] freezeTimes;

    /** True iff the dealer is currently placing cards on the table. */
    public boolean currentlyPlacingCards;

    /** A queue of players that want to be checked. */
    private Queue<Integer> waitingPlayers;

    /** A semaphore used to manage the waiting players queue correctly. */
    private Semaphore QSemaphore;

    private static final int BREAK_MILLIS = 25;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.dealerSleepLock = new Object();
        this.freezeTimes = new long[env.config.players];
        this.currentlyPlacingCards = true;
        this.waitingPlayers = new ArrayBlockingQueue<Integer>(env.config.players);
        this.QSemaphore = new Semaphore(1, true); // allows only one thread at a time an access to the queue
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        // create the player threads and starts them

        for (Player player : players) {
            Thread playerThread = new Thread(player);
            playerThread.start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            removeAllCardsFromTable();
        }

        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime && table.countCards() != 0) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int i = env.config.players - 1; i >= 0; i--) {
            players[i].terminate();
        }

        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     * 
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        Integer currentPlayer;
        int[] currentPlayerCards = new int[Table.SET_SIZE];
        int i;

        try {
            QSemaphore.acquire();

            while (!waitingPlayers.isEmpty()) {
                currentPlayer = waitingPlayers.poll();

                i = 0; // this is the index of the last placed card in the array

                // find the cards that the current player chose
                for (int slot = 0; slot < env.config.tableSize; slot++) {
                    // if the current player placed a token in this slot, add the card to the array
                    if (table.tokens[slot][currentPlayer]) {
                        currentPlayerCards[i++] = table.slotToCard[slot];
                    }
                }

                // now check the set. it may contain less than 3 cards if another player chose a
                // card that was part of the set this player chos, but made it quicker.

                if (i == 3) {
                    if (env.util.testSet(currentPlayerCards)) {
                        players[currentPlayer].foundSet = Player.LEGAL_SET;

                        for (int card : currentPlayerCards) {
                            table.removeCard(table.cardToSlot[card]);
                        }

                        freezeTimes[currentPlayer] = System.currentTimeMillis() + env.config.pointFreezeMillis;
                        updateTimerDisplay(true);
                    } else {
                        players[currentPlayer].foundSet = Player.NOT_LEGAL;
                        freezeTimes[currentPlayer] = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
                    }
                }

                // signal the player that its set has been checked
                synchronized (players[currentPlayer].playerTestLock) {
                    players[currentPlayer].playerTestLock.notify();
                }
            }

            QSemaphore.release();
        } catch (InterruptedException e) {

        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);

        for (int slot = 0; slot < env.config.tableSize; slot++) {
            if (table.slotToCard[slot] == null && !deck.isEmpty()) {
                table.placeCard(deck.remove(0), slot);
            }
        }

        // done filling the table
        currentlyPlacingCards = false;
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            synchronized (dealerSleepLock) {
                dealerSleepLock.wait(BREAK_MILLIS);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        } else {
            long timeLeft = reshuffleTime - System.currentTimeMillis();
            env.ui.setCountdown(timeLeft < 0 ? 0 : timeLeft, timeLeft < env.config.turnTimeoutWarningMillis);

            for (int player = 0; player < env.config.players; player++) {
                if (freezeTimes[player] != 0) {
                    env.ui.setFreeze(player, freezeTimes[player] - System.currentTimeMillis());

                    // if the freeze time has passed, set it to 0 update the UI
                    if (freezeTimes[player] <= System.currentTimeMillis()) {
                        freezeTimes[player] = 0;
                        env.ui.setFreeze(player, 0);
                    }
                }
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // here, we start updating the table
        currentlyPlacingCards = true;

        for (int slot = 0; slot < env.config.tableSize; slot++) {
            if (table.slotToCard[slot] != null) {
                deck.add(table.slotToCard[slot]);
                table.removeCard(slot);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxPoints = 0, counter = 0, i = 0;
        int[] winners;

        // find the max score and the number of players that achieved it
        for (Player player : players) {
            if (player.score() > maxPoints) {
                counter = 1;
                maxPoints = player.score();
            } else if (player.score() == maxPoints) {
                counter++;
            }
        }

        // create an array of winners and make an announcement

        winners = new int[counter];

        for (Player player : players) {
            if (player.score() == maxPoints) {
                winners[i++] = player.id;
            }
        }

        env.ui.announceWinner(winners);
    }

    /**
     * Add a player to the queue, and make sure that one player is added at a time.
     * 
     * @param id the id of the player to add.
     */
    public void addPlayerToQueue(int id) {
        try {
            QSemaphore.acquire();

            waitingPlayers.add(id);

            synchronized (dealerSleepLock) {
                dealerSleepLock.notify();
            }

            QSemaphore.release();
        } catch (InterruptedException e) {

        }
    }
}
