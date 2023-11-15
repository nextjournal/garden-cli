(ns nextjournal.edit-distance)

(defn edit-distance [a b]
  (let [alen (count a)
        blen (count b)
        [longlen shortlen a b] (if (> alen blen)
                                 [alen blen a b]
                                 [blen alen b a])
        [a' & a-rest] a
        [b' & b-rest] b]
    (if (zero? shortlen)
      longlen
      (if (= a' b')
        (edit-distance a-rest b-rest)
        (+ 1 (min (edit-distance a-rest b-rest)
                  (edit-distance a-rest b)))))))

(def max-edit-distance 3)
(def max-candidates 3)

(defn candidates [input available-cmds]
  (->> available-cmds
       (map (fn [c] {:dist (edit-distance c input)
                     :cmd c}))
       (filter (fn [{:keys [dist]}] (<= dist max-edit-distance)))
       (sort-by :dist)
       (map :cmd)
       (take max-candidates)))
