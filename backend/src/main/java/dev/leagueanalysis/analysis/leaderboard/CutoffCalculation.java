package dev.leagueanalysis.analysis.leaderboard;

import java.util.*;

/** Compact population aggregates; no provider identity survives aggregation. */
final class CutoffCalculation {
    record Bucket(int lp,int count) {}
    record Result(Leaderboard.Cutoff challenger,Leaderboard.Cutoff grandmaster) {}
    static List<Bucket> histogram(List<LeaderboardClient.Standing> standings) {
        var counts=new TreeMap<Integer,Integer>(Comparator.reverseOrder());
        for(var row:standings)counts.merge(row.lp(),1,Math::addExact);
        return counts.entrySet().stream().map(e->new Bucket(e.getKey(),e.getValue())).toList();
    }
    static Result calculate(List<Bucket> challenger,List<Bucket> grandmaster,List<Bucket> master) {
        var counts=new TreeMap<Integer,Integer>(Comparator.reverseOrder());
        for(var group:List.of(challenger,grandmaster,master))for(var bucket:group) {
            if(bucket.lp()<0||bucket.count()<1)throw new IllegalArgumentException("INVALID_CUTOFF_AGGREGATE");
            counts.merge(bucket.lp(),bucket.count(),Math::addExact);
        }
        long challengerEligible=counts.entrySet().stream().filter(e->e.getKey()>=800).mapToLong(Map.Entry::getValue).sum();
        int occupied=(int)Math.min(300,challengerEligible);
        return new Result(boundary(counts,300,300,800),boundary(counts,occupied+700,700,400));
    }
    private static Leaderboard.Cutoff boundary(SortedMap<Integer,Integer> counts,int rank,int slots,int minimum) {
        long ahead=0;
        for(var bucket:counts.entrySet()) {
            if(bucket.getKey()<minimum)break;
            ahead+=bucket.getValue();
            if(ahead>=rank)return new Leaderboard.Cutoff(slots,minimum,bucket.getKey(),Math.max(minimum,Math.addExact(bucket.getKey(),1)));
        }
        return new Leaderboard.Cutoff(slots,minimum,null,minimum);
    }
}
