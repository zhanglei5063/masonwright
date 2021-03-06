package coalitiongames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;

import coalitiongames.PriceWithError.PriceUpdateSource;

public abstract class TabuSearch {
    
    public static final int DEFAULT_TABU_STEPS = 20;
    // public static final int DEFAULT_TABU_STEPS = 10; // for quick testing
    
    public static void main(final String[] args) {
        final Integer[] myArr = {7, 2, 1, 11, 12, 13};
        List<Integer> testList = Arrays.asList(myArr);
        System.out.println(getMinMaxPairs(testList));
        
        final Integer[] myArr2 = {1, 2, 3, 4, 11, 14, 15, 16, 17};
        testList = Arrays.asList(myArr2);
        System.out.println(getMinMaxPairs(testList));
    }

    /**
     * convenience method that calls tabuSearch() with default queue length
     * and maximum number of steps.
     * 
     * @param agents a list of all agents with their budgets and preferences
     * @param gammaZ an error function to use for updating prices
     * @param kMax maximum agents per team, including self
     * @param kMin minimum agents per team, including self
     * @return a SearchResult, including an allocation, price vector,
     * and other data
     */
    public static SearchResult tabuSearch(
        final List<Agent> agents,
        final GammaZ gammaZ,
        final int kMax,
        final int kMin
    ) {
        final int defaultQueueLength = DEFAULT_TABU_STEPS;
        final int defaultMaxSteps = DEFAULT_TABU_STEPS;
        return tabuSearch(
            defaultQueueLength, 
            defaultMaxSteps, 
            agents, 
            gammaZ, 
            kMax, 
            kMin
        );
    }
    
    public static SearchResult tabuSearchRanges(
        final List<Agent> agents,
        final GammaZ gammaZ,
        final List<Integer> teamSizes
    ) {
        final int defaultQueueLength = DEFAULT_TABU_STEPS;
        final int defaultMaxSteps = DEFAULT_TABU_STEPS;
        final double maxPrice = 
            MipGenerator.MIN_BUDGET + MipGenerator.MIN_BUDGET / agents.size();
        return tabuSearchRanges(
            defaultQueueLength, 
            defaultMaxSteps, 
            agents, 
            gammaZ, 
            teamSizes,
            getInitialPriceWithError(teamSizes, agents, maxPrice, gammaZ)
        );
    }
    
    /**
     * 
     * @param queueLength maximum length of drop-out tabu queue
     * @param maxSteps maximum number of update steps to perform
     * @param agents a list of all agents with their budgets and preferences
     * @param gammaZ an error function to use for updating prices
     * @param teamSizes a list of lists of integers, where each list
     * has 2 elements, the first of which is a kMin and the second a kMax,
     * kMin <= kMax. each list represents 1 range of allowable sizes for
     * the "next" team, based on RsdUtil.getFeasibleNextTeamSizes(), and
     * TabuSearch.getMinMaxPairs().
     * @return a SearchResult, including an allocation, price vector,
     * and other data
     * @return
     */
    public static SearchResult tabuSearchRanges(
        final int queueLength,
        final int maxSteps,
        final List<Agent> agents,
        final GammaZ gammaZ,
        final List<Integer> teamSizes,
        final PriceWithError initialPriceWithError
    ) {
        assert queueLength > 0;
        assert agents != null && agents.size() > 0;
        assert gammaZ != null;
        final int n = agents.size();
        assert teamSizes != null && !teamSizes.isEmpty();
        
        // time the duration of the search to the millisecond
        final long searchStartMillis = new Date().getTime();
        final double maxPrice = 
            MipGenerator.MIN_BUDGET + MipGenerator.MIN_BUDGET / n;
        final double maxBudget = maxPrice;
        
        PriceWithError currentNode = initialPriceWithError;
        PriceWithError bestNode = currentNode;
        
        final List<Double> bestErrorValues = new ArrayList<Double>();
        final List<PriceUpdateSource> priceUpdateSources =
            new ArrayList<PriceUpdateSource>();
        final Queue<PriceWithError> tabuQueue = 
            new DropOutQueue<PriceWithError>(queueLength);
        if (MipGenerator.DEBUGGING) {
            System.out.println("Best error: " + bestNode.getErrorValue());
        }
        int step = 0;
        // stop searching if no error at best node
        while (bestNode.getErrorValue() > 0.0) {
            // add current node to tabu queue so it won't be revisited
            tabuQueue.add(currentNode);
            bestErrorValues.add(bestNode.getErrorValue());
            priceUpdateSources.add(currentNode.getPriceUpdateSource());
            step++;
            if (step > maxSteps) {
                break;
            }
            // get neighbors of currentNode, sorted by increasing error
            final List<PriceWithError> sortedNeighbors = 
                NeighborGenerator.sortedNeighbors(
                    currentNode.getPrices(), 
                    currentNode.getError(), 
                    maxPrice, 
                    agents, 
                    gammaZ, 
                    teamSizes
                );
            // check if any neighbor is not already in the tabu queue
            boolean newNeighborFound = false;
            for (final PriceWithError neighbor: sortedNeighbors) {
                // check if neighbor is not in the tabu queue
                if (!tabuQueue.contains(neighbor)) {
                    currentNode = neighbor;
                    // found a neighbor not already in the tabu queue
                    newNeighborFound = true;
                    if (MipGenerator.DEBUGGING) {
                        System.out.println("Step: " + step);
                        System.out.println(
                            "Current error: " + currentNode.getErrorValue()
                        );
                    }
                    if (
                        currentNode.getErrorValue() < bestNode.getErrorValue()
                    ) {
                        bestNode = currentNode;
                        if (MipGenerator.DEBUGGING) {
                            System.out.println(
                                "Best error: " + bestNode.getErrorValue()
                            );
                        }
                    }
                    // stop searching neighbors for best one not in tabu queue
                    break;
                }
            }
            if (!newNeighborFound) {
                // all neighbors are in the tabu queue
                break;
            }
            if (bestNode != currentNode && !tabuQueue.contains(bestNode)) {
                // no better neighbors found in last "queueLength" steps
                break;
            }
        }
        
        // search complete. return best node.
        
        final double similarity = 
            PreferenceAnalyzer.getMeanPairwiseCosineSimilarity(agents);
        
        final long searchDurationMillis = 
            new Date().getTime() - searchStartMillis;
        final SearchResult result = new SearchResult(
            bestNode.getPrices(), 
            bestNode.getDemand(), 
            bestNode.getError(), 
            bestNode.getErrorValue(),
            maxBudget, 
            agents,
            searchDurationMillis,
            null,
            bestErrorValues,
            priceUpdateSources,
            1,
            null,
            similarity
        );
        return result;
    }
    
    /**
     * 
     * @param queueLength maximum length of drop-out tabu queue
     * @param maxSteps maximum number of update steps to perform
     * @param agents a list of all agents with their budgets and preferences
     * @param gammaZ an error function to use for updating prices
     * @param kMax maximum agents per team, including self
     * @param kMin minimum agents per team, including self
     * @return a SearchResult, including an allocation, price vector,
     * and other data
     * @return
     */
    public static SearchResult tabuSearch(
        final int queueLength,
        final int maxSteps,
        final List<Agent> agents,
        final GammaZ gammaZ,
        final int kMax,
        final int kMin
    ) {
        assert queueLength > 0;
        final int minimumAgents = 4;
        assert agents != null && agents.size() >= minimumAgents;
        assert gammaZ != null;
        final int n = agents.size();
        assert kMax <= n;
        assert kMax >= kMin;
        assert kMin >= 0;
        assert checkKRange(n, kMin, kMax);
        
        // time the duration of the search to the millisecond
        final long searchStartMillis = new Date().getTime();
        final double maxPrice = 
            MipGenerator.MIN_BUDGET + MipGenerator.MIN_BUDGET / n;
        final double maxBudget = maxPrice;
        PriceWithError currentNode = 
            getInitialPriceWithError(kMax, kMin, agents, maxPrice, gammaZ);
        PriceWithError bestNode = currentNode;
        
        final List<Double> bestErrorValues = new ArrayList<Double>();
        final List<PriceUpdateSource> priceUpdateSources =
            new ArrayList<PriceUpdateSource>();
        final Queue<PriceWithError> tabuQueue = 
            new DropOutQueue<PriceWithError>(queueLength);
        if (MipGenerator.DEBUGGING) {
            System.out.println("Best error: " + bestNode.getErrorValue());
        }
        int step = 0;
        // stop searching if no error at best node
        while (bestNode.getErrorValue() > 0.0) {
            // add current node to tabu queue so it won't be revisited
            tabuQueue.add(currentNode);
            bestErrorValues.add(bestNode.getErrorValue());
            priceUpdateSources.add(currentNode.getPriceUpdateSource());
            step++;
            if (step > maxSteps) {
                break;
            }
            // get neigbors of currentNode, sorted by increasing error
            final List<PriceWithError> sortedNeighbors = 
                NeighborGenerator.sortedNeighbors(
                    currentNode.getPrices(), 
                    currentNode.getError(), 
                    maxPrice, 
                    agents, 
                    gammaZ, 
                    kMax, 
                    kMin
                );
            // check if any neighbor is not already in the tabu queue
            boolean newNeighborFound = false;
            for (final PriceWithError neighbor: sortedNeighbors) {
                // check if neighbor is not in the tabu queue
                if (!tabuQueue.contains(neighbor)) {
                    currentNode = neighbor;
                    // found a neighbor not already in the tabu queue
                    newNeighborFound = true;
                    if (MipGenerator.DEBUGGING) {
                        System.out.println("Step: " + step);
                        System.out.println(
                            "Current error: " + currentNode.getErrorValue()
                        );
                    }
                    if (
                        currentNode.getErrorValue() < bestNode.getErrorValue()
                    ) {
                        bestNode = currentNode;
                        if (MipGenerator.DEBUGGING) {
                            System.out.println(
                                "Best error: " + bestNode.getErrorValue()
                            );
                        }
                    }
                    // stop searching neighbors for best one not in tabu queue
                    break;
                }
            }
            if (!newNeighborFound) {
                // all neighbors are in the tabu queue
                break;
            }
            if (bestNode != currentNode && !tabuQueue.contains(bestNode)) {
                // no better neighbors found in last "queueLength" steps
                break;
            }
        }
        
        // search complete. return best node.
        
        final double similarity = 
            PreferenceAnalyzer.getMeanPairwiseCosineSimilarity(agents);
        
        final long searchDurationMillis = 
            new Date().getTime() - searchStartMillis;
        final SearchResult result = new SearchResult(
            bestNode.getPrices(), 
            bestNode.getDemand(), 
            bestNode.getError(), 
            bestNode.getErrorValue(), 
            kMin, 
            kMax, 
            maxBudget, 
            agents,
            searchDurationMillis,
            null,
            bestErrorValues,
            priceUpdateSources,
            1,
            null,
            similarity
        );
        return result;
    }
    
    /**
     * 
     * @param n total count of agents, NOT excluding the self agent
     * @param kMin proposed minimum agents per team, including self
     * @param kMax proposed maximum agents per tema, including self
     * @return true if n agents can be split into teams of size
     * in {kMin, kMin + 1, . . ., kMax}.
     * this is true if kMin divides n, kMax divides n,
     * or n \ kMin != n \ kMax.
     */
    public static boolean checkKRange(
        final int n,
        final int kMin,
        final int kMax
    ) {
        if (kMin < 0 || kMin > kMax) {
            if (kMin < 0) {
                System.out.println("Min below 0: " + kMin);
            }
            if (kMin > kMax) {
                System.out.println("Min above max: " + kMin + " " + kMax);
            }
            throw new IllegalArgumentException();
        }
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        
        return (kMin < 2) || (n % kMin == 0) 
            || (n % kMax == 0) || (n / kMin != n / kMax);
    }
    
    /**
     * @param legalSizes a list of all legal values, as integers
     * @return a list of lists, where each sublist is of length 2. each sublist
     * contains a first item that is the minimum of a range, and a second item
     * that is a maximum of the same range, possibly equal to the minimum.
     * when all the ranges in the sublists are
     * combined, their union over the integers 
     * produces the original list without
     * duplicates.
     * example input: {7, 2, 1, 11, 12, 13}
     * example output:
     * { {1, 2}, {7, 7}, {11, 13} }.
     * Note that 7 appears as the minimum and maximum of one list.
     * 12 does not appear explicitly, but it is between 11 and 13.
     * 
     * This function is used to produce 1 subset membership constraint 
     * for an MIP, by breaking the MIP with 1 subset membership constraint 
     * into a list of MIP's with linear constraints, one for each subset,
     * and returning the MIP result with the greatest objective
     * value.
     */
    public static List<List<Integer>> getMinMaxPairs(
        final List<Integer> legalSizes
    ) {
        assert legalSizes != null;
        assert !legalSizes.isEmpty();
        
        if (MipGenerator.DEBUGGING) {
            for (Integer legalSize: legalSizes) {
                if (legalSize <= 0) {
                    throw new IllegalArgumentException();
                }
            }
        }
        
        // sort sizes ascending.
        Collections.sort(legalSizes);
        final List<List<Integer>> result = new ArrayList<List<Integer>>();
        // index of the minimum value of the next range
        int minIndex = 0;
        while (minIndex < legalSizes.size()) {
            final int currentMin = legalSizes.get(minIndex);
            int tempMax = currentMin;
            // iterate over later items in the sorted legalSizes, until the next
            // higher value is not 1 greater than the previous value.
            int maxIndex = minIndex + 1;
            while (
                maxIndex < legalSizes.size() 
                && legalSizes.get(maxIndex) == tempMax + 1
            ) {
                tempMax = legalSizes.get(maxIndex);
                maxIndex++;
            }
            
            final List<Integer> newList = new ArrayList<Integer>();
            newList.add(currentMin);
            newList.add(tempMax);
            result.add(newList);
            
            // maxIndex has already been incremented after the last update, 
            // so there is no need to increment it again here.
            minIndex = maxIndex;
        }
        
        return result;
    }
    
    public static List<Integer> getSizesFromMinMaxPairs(
        final List<List<Integer>> pairs
    ) {
        final List<Integer> result = new ArrayList<Integer>();
        for (final List<Integer> pair: pairs) {
            for (int i = pair.get(0); i <= pair.get(1); i++) {
                result.add(i);
            }
        }
        
        return result;
    }
    
    /**
     * @param teamSizeRanges list of lists of integers, where each list
     * has two items and is a (min, max) pair of one size range. acceptable
     * sizes are these pairs and any integer between a min and its max.
     * @param agents a list of all agents with their budgets and preferences
     * @param maxPrice maximum price an agent can be given
     * @param gammaZ used to evaluate error of a given allocation
     * @return a price vector for the agents, along with the aggregate
     * demand it induces, the error according to gammaZ of that demand,
     * and the l2-norm of this error.
     */
    private static PriceWithError getInitialPriceWithError(
        final List<Integer> teamSizes,
        final List<Agent> agents,
        final double maxPrice,
        final GammaZ gammaZ
    ) {
        int kMax = getKMax(teamSizes);
        int kMin = getKMin(teamSizes);
        final List<Double> prices = new ArrayList<Double>();
        final double basePrice = MipGenerator.MIN_BUDGET / kMax;
        for (int i = 1; i <= agents.size(); i++) {
            prices.add(basePrice);
        }
        final DemandGenerator demandGen = 
            DemandGeneratorMultiCore.getDemandGenerator();
        final List<List<Integer>> aggregateDemand = 
            demandGen.getAggregateDemand(
                agents, 
                prices, 
                teamSizes, 
                maxPrice
            );
        final List<Double> errorDemand = 
            gammaZ.z(aggregateDemand, prices, kMax, kMin, maxPrice);
        final double error = DemandAnalyzer.errorSizeDouble(errorDemand);
        return new PriceWithError(
            prices, errorDemand, aggregateDemand, 
            error, PriceUpdateSource.INITIAL
        );
    }
    
    public static int getKMin(final List<Integer> teamSizes) {
        int kMin = Integer.MAX_VALUE;
        for (final Integer teamSize: teamSizes) {
            if (teamSize < kMin) {
                kMin = teamSize;
            }
        }
        return kMin;
    }
    
    public static int getKMax(final List<Integer> teamSizes) {
        int kMax = 0;
        for (final Integer teamSize: teamSizes) {
            if (teamSize > kMax) {
                kMax = teamSize;
            }
        }
        return kMax;
    }
    
    /**
     * @param kMax maximum agents per team, including self
     * @param kMin minimum agents per team, including self
     * @param agents a list of all agents with their budgets and preferences
     * @param maxPrice maximum price an agent can be given
     * @param gammaZ used to evaluate error of a given allocation
     * @return a price vector for the agents, along with the aggregate
     * demand it induces, the error according to gammaZ of that demand,
     * and the l2-norm of this error.
     */
    private static PriceWithError getInitialPriceWithError(
        final int kMax,
        final int kMin,
        final List<Agent> agents,
        final double maxPrice,
        final GammaZ gammaZ
    ) {
        final List<Double> prices = new ArrayList<Double>();
        final double basePrice = MipGenerator.MIN_BUDGET / kMax;
        for (int i = 1; i <= agents.size(); i++) {
            prices.add(basePrice);
        }
        final DemandGenerator demandGen = 
            DemandGeneratorMultiCore.getDemandGenerator();
        final List<List<Integer>> aggregateDemand = 
            demandGen.getAggregateDemand(
                agents, 
                prices, 
                kMax, 
                kMin, 
                maxPrice
            );
        final List<Double> errorDemand = 
            gammaZ.z(aggregateDemand, prices, kMax, kMin, maxPrice);
        final double error = DemandAnalyzer.errorSizeDouble(errorDemand);
        return new PriceWithError(
            prices, errorDemand, aggregateDemand, 
            error, PriceUpdateSource.INITIAL
        );
    }
}
