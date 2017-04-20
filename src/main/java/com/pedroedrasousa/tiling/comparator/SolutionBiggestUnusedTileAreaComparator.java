package com.pedroedrasousa.tiling.comparator;

import com.pedroedrasousa.tiling.model.Solution;

import java.util.Comparator;

public class SolutionBiggestUnusedTileAreaComparator implements Comparator<Solution> {
    @Override
    public int compare(Solution o1, Solution o2) {
        return o2.getBiggestArea() - o1.getBiggestArea();
    }
}
