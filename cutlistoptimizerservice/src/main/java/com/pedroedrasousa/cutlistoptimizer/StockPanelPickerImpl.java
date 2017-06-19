package com.pedroedrasousa.cutlistoptimizer;

import com.pedroedrasousa.cutlistoptimizer.model.TileDimensions;

import java.util.*;

public class StockPanelPickerImpl implements StockPanelPicker {

    private static final StockPanelPicker instance = new StockPanelPickerImpl();

    public static StockPanelPicker getInstance() {
        return instance;
    }

    private boolean isExcluded(StockSolution stockSolution, List<StockSolution> excludedStockSolutions) {
        // Loop through excluded stock solutions and compare them against the candidate one
        for (StockSolution excludedStockSolution : excludedStockSolutions) {
            if (excludedStockSolution.equals(stockSolution)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcluded(List<TileDimensions> stockTiles, List<StockSolution> excludedStockSolutions, List<Integer> stockTilesIndexes) {

        if (excludedStockSolutions == null || excludedStockSolutions.size() == 0) {
            return false;
        }

        // Build a stock solution object with the candidate stock tile indexes to compare against the excluded stock solutions
        List<TileDimensions> stockSolutions = new ArrayList<>();
        for (Integer i : stockTilesIndexes) {
            stockSolutions.add(stockTiles.get(i));
        }
        StockSolution candidateStockSolution = new StockSolution(stockSolutions);

        return isExcluded(candidateStockSolution, excludedStockSolutions);
    }


    private Integer getNextUnusedStockTile(int nbrStockTiles, List<Integer> stockTilesIndexes, int index) {
        for (int i = index + 1; i < nbrStockTiles; i++) {
            if (!stockTilesIndexes.contains(i)) {
                return i;
            }
        }
       return null;
    }

    /**
     * Computes the best stock solution based on the specified criteria.
     *
     * @param stockTiles Available stock tiles to be used
     * @param requiredArea Area required by the pretended tiles
     * @param nbrTiles Number of stock panels to be used
     * @param exclusions Stock solutions to exclude.
     * @return The computed stock solution, null if the specified criterias could not be fulfilled.
     */
    private StockSolution getCandidateStockSolution(List<TileDimensions> stockTiles, int requiredArea, int requiredMaxDimension, int nbrTiles, List<StockSolution> exclusions) {
        List<Integer> stockTilesIndexes = new ArrayList<>();

        // Sort base tiles by area, least area first.
        // Java 8
        //stockTiles.sort(Comparator.comparingInt(TileDimensions::getArea));
        Collections.sort(stockTiles, new Comparator<TileDimensions>() {
            public int compare(TileDimensions td1, TileDimensions td2) {
                return Integer.compare(td1.getArea(), td2.getArea());
            }
        });

        // Start with the stock panels with least area
        for (int i = 0; i < nbrTiles; i++) {
            stockTilesIndexes.add(i);
        }

        return iterate(stockTiles, requiredArea, requiredMaxDimension, nbrTiles, exclusions, stockTilesIndexes, 0);
    }


    private StockSolution iterate(List<TileDimensions> stockTiles, int requiredArea, int requiredMaxDimension, int nbrTiles, List<StockSolution> exclusions, List<Integer> stockTilesIndexes, int indexToIterate) {
        Integer nextSpareTileIndex = null;

        //boolean fits = false;

        // If there are repeated stock tiles previously to the index in the solution discard it
        HashSet<Integer> temp = new HashSet<>();
        for (int i = 0; i < indexToIterate; i++) {
            if (temp.add(stockTilesIndexes.get(i)) == false) {
                return null;
            }
        }

        if (indexToIterate < nbrTiles - 1) {
            for (int j = 0; j < stockTiles.size(); j++) {
                StockSolution stockSolution = iterate(stockTiles, requiredArea, requiredMaxDimension, nbrTiles, exclusions, stockTilesIndexes, indexToIterate + 1);
                if (stockSolution != null) {
                    return stockSolution;
                }
                stockTilesIndexes.set(indexToIterate, j);       // Next index
                stockTilesIndexes.set(indexToIterate + 1, 0);   // Reset the index ahead
            }
        }

        // Keep incrementing the stock tile being iterated until required area is met or the biggest tile is reached.
        do {
            boolean fits = false;

            // Check if the current set of stock tiles meet the required area
            int remainingRequiredArea = requiredArea;
            for (Integer sol : stockTilesIndexes) {
                remainingRequiredArea -= stockTiles.get(sol).getArea();

                //  Check if this stock tile has the maximum required dimension
                if (stockTiles.get(sol).getMaxDimension() >= requiredMaxDimension) {
                    fits = true;
                }
            }

            System.out.println(stockTilesIndexes);

            // If required area is met and solution is not excluded, build an array with the tile dimensions and return it.
            if (remainingRequiredArea <= 0 && fits && !isExcluded(stockTiles, exclusions, stockTilesIndexes)) {
                StockSolution stockSolution = new StockSolution();
                for (Integer sol : stockTilesIndexes) {
                    stockSolution.addStockTile(stockTiles.get(sol));
                }
                return stockSolution;
            }

            // Use the next size for the stock tile number being iterated
            nextSpareTileIndex = getNextUnusedStockTile(stockTiles.size(), stockTilesIndexes, stockTilesIndexes.get(indexToIterate));
            if (nextSpareTileIndex != null) {
                if (indexToIterate == 2 && nbrTiles == 2 && stockTilesIndexes.get(0) == 110) {
                    nextSpareTileIndex = nextSpareTileIndex;
                }
                stockTilesIndexes.set(indexToIterate, nextSpareTileIndex);
            }

        } while (nextSpareTileIndex != null);

        return null;
    }

    public StockSolution getCandidateStockSolutions(List<TileDimensions> tilesToFit, List<TileDimensions> stockTiles, float areaDelta, int nbrSpare, List<StockSolution> exclusions, int minNbrPanels, int maxNbrTiles) {

        StockSolution stockSolution;

        // Sort base tiles by area, least area first.
        // Java 8
        //stockTiles.sort(Comparator.comparingInt(TileDimensions::getArea));
        Collections.sort(stockTiles, new Comparator<TileDimensions>() {
            public int compare(TileDimensions td1, TileDimensions td2) {
                return Integer.compare(td1.getArea(), td2.getArea());
            }
        });

        int requiredMaxDimension = 0;

        // Calculate the required area for fitting every tile.
        int requiredArea = 0;
        for (TileDimensions tile : tilesToFit) {
            requiredArea += tile.getArea();
            if (tile.getMaxDimension() > requiredMaxDimension) {
                requiredMaxDimension = tile.getMaxDimension();
            }
        }

        // Add required delta to area
        requiredArea = (int)(requiredArea * (1.0f + areaDelta));

        // Try to match required area with the least possible number of stock tiles.
        // Start with one stock tile and increment until required area is met.
        // Resulting solution will be returned if no spare is requested.
        int nbrTiles;
        for (nbrTiles = minNbrPanels; nbrTiles < stockTiles.size() && nbrTiles <= maxNbrTiles; nbrTiles++) {
            stockSolution = getCandidateStockSolution(stockTiles, requiredArea, requiredMaxDimension, nbrTiles, exclusions);
            if (stockSolution != null) {
                if (nbrSpare == 0) {
                    return stockSolution;
                }
                break;
            }
        }

        // If at least one spare stock panel was requested and there are still stock panels remaining build a solution
        if (nbrSpare > 0 && nbrTiles < stockTiles.size() && nbrTiles + nbrSpare < maxNbrTiles) {
            stockSolution = getCandidateStockSolution(stockTiles, requiredArea, requiredMaxDimension, nbrTiles + nbrSpare, exclusions);
            if (stockSolution != null) {
                return stockSolution;
            }
        }

        // Couldn't find stock tiles to fit the required area
        // Return biggest stock tiles as last resort if not in exclusions
        stockSolution = new StockSolution();
        for (int i = 0; i < stockTiles.size() && i < maxNbrTiles; i++) {
            stockSolution.addStockTile(stockTiles.get(stockTiles.size() - i - 1));
        }
        if (!isExcluded(stockSolution, exclusions)) {
            return stockSolution;
        }

        return null;
    }
}
