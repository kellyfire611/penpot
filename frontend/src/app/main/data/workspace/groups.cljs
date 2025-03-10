(ns app.main.data.workspace.groups
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (map #(get objects %))
       (filter #(not= :frame (:type %)))
       (map #(assoc % ::index (cp/position-on-parent (:id %) objects)))
       (sort-by ::index)))

(defn- make-group
  [shapes prefix keep-name]
  (let [selrect   (gsh/selection-rect shapes)
        frame-id  (-> shapes first :frame-id)
        parent-id (-> shapes first :parent-id)
        group-name (if (and keep-name
                            (= (count shapes) 1)
                            (= (:type (first shapes)) :group))
                     (:name (first shapes))
                     (name (gensym prefix)))] ; TODO: we should something like in new shapes
    (-> (cp/make-minimal-group frame-id selrect group-name)
        (gsh/setup selrect)
        (assoc :shapes (mapv :id shapes)))))

(defn get-empty-groups
  "Retrieve emtpy groups after group creation"
  [objects parent-id shapes]
  (let [ids (cp/clean-loops objects (into #{} (map :id) shapes))
        parents (->> ids
                     (reduce #(conj %1 (cp/get-parent %2 objects))
                             #{}))]
    (loop [current-id (first parents)
           to-check (rest parents)
           removed-id? ids
           result #{}]

      (if-not current-id
        ;; Base case, no next element
        result

        (let [group (get objects current-id)]
          (if (and (not= :frame (:type group))
                   (not= current-id parent-id)
                   (empty? (remove removed-id? (:shapes group))))

            ;; Adds group to the remove and check its parent
            (let [to-check (d/concat [] to-check [(cp/get-parent current-id objects)]) ]
              (recur (first to-check)
                     (rest to-check)
                     (conj removed-id? current-id)
                     (conj result current-id)))

            ;; otherwise recur
            (recur (first to-check)
                   (rest to-check)
                   removed-id?
                   result)))))))

(defn prepare-create-group
  [objects page-id shapes prefix keep-name]
  (let [group (make-group shapes prefix keep-name)
        frame-id (:frame-id (first shapes))
        parent-id (:parent-id (first shapes))
        rchanges [{:type :add-obj
                   :id (:id group)
                   :page-id page-id
                   :frame-id frame-id
                   :parent-id parent-id
                   :obj group
                   :index (::index (first shapes))}

                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes (mapv :id shapes)}]

        uchanges  (-> (mapv
                       (fn [obj]
                         {:type :mov-objects
                          :page-id page-id
                          :parent-id (:parent-id obj)
                          :index (::index obj)
                          :shapes [(:id obj)]}) shapes)
                      (conj
                       {:type :del-obj
                        :id (:id group)
                        :page-id page-id}))

        ids-to-delete (get-empty-groups objects parent-id shapes)

        delete-group
        (fn [changes id]
          (-> changes
              (conj {:type :del-obj
                     :id id
                     :page-id page-id})))

        add-deleted-group
        (fn [changes id]
          (let [obj (-> (get objects id)
                        (d/without-keys [:shapes]))]

            (d/concat [{:type :add-obj
                        :id id
                        :page-id page-id
                        :frame-id (:frame-id obj)
                        :parent-id (:parent-id obj)
                        :obj obj
                        :index (::index obj)}] changes)))

        rchanges (->> ids-to-delete
                      (reduce delete-group rchanges))

        uchanges (->> ids-to-delete
                      (reduce add-deleted-group uchanges))]
    [group rchanges uchanges]))

(defn prepare-remove-group
  [page-id group objects]
  (let [shapes    (:shapes group)
        parent-id (cp/get-parent (:id group) objects)
        parent    (get objects parent-id)
        index-in-parent (->> (:shapes parent)
                             (map-indexed vector)
                             (filter #(#{(:id group)} (second %)))
                             (ffirst))
        rchanges [{:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes shapes
                   :index index-in-parent}
                  {:type :del-obj
                   :page-id page-id
                   :id (:id group)}]
        uchanges [{:type :add-obj
                   :page-id page-id
                   :id (:id group)
                   :frame-id (:frame-id group)
                   :obj (assoc group :shapes [])}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes shapes}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes [(:id group)]
                   :index index-in-parent}]]
    [rchanges uchanges]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def group-selected
  (ptk/reify ::group-selected
    ptk/WatchEvent
    (watch [it state stream]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            selected (cp/clean-loops objects selected)
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [[group rchanges uchanges]
                (prepare-create-group objects page-id shapes "Group-" false)]
            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

(def ungroup-selected
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [it state stream]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            group-id (first selected)
            group    (get objects group-id)]
        (when (and (= 1 (count selected))
                   (= (:type group) :group))
          (let [[rchanges uchanges]
                (prepare-remove-group page-id group objects)]
            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it}))))))))

(def mask-group
  (ptk/reify ::mask-group
    ptk/WatchEvent
    (watch [it state stream]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)
            selected (cp/clean-loops objects selected)
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; create a new group and set it as masked.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (prepare-create-group objects page-id shapes "Group-" true))

                rchanges (d/concat rchanges
                                   [{:type :mod-obj
                                     :page-id page-id
                                     :id (:id group)
                                     :operations [{:type :set
                                                   :attr :masked-group?
                                                   :val true}
                                                  {:type :set
                                                   :attr :selrect
                                                   :val (-> shapes first :selrect)}
                                                  {:type :set
                                                   :attr :points
                                                   :val (-> shapes first :points)}
                                                  {:type :set
                                                   :attr :transform
                                                   :val (-> shapes first :transform)}
                                                  {:type :set
                                                   :attr :transform-inverse
                                                   :val (-> shapes first :transform-inverse)}]}
                                    {:type :reg-objects
                                     :page-id page-id
                                     :shapes [(:id group)]}])

                uchanges (conj uchanges
                               {:type :mod-obj
                                :page-id page-id
                                :id (:id group)
                                :operations [{:type :set
                                              :attr :masked-group?
                                              :val nil}]}
                               {:type :reg-objects
                                :page-id page-id
                                :shapes [(:id group)]})]

            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

(def unmask-group
  (ptk/reify ::unmask-group
    ptk/WatchEvent
    (watch [it state stream]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected (wsh/lookup-selected state)]
        (when (= (count selected) 1)
          (let [group (get objects (first selected))

                rchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val nil}]}
                          {:type :reg-objects
                           :page-id page-id
                           :shapes [(:id group)]}]

                uchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val (:masked-group? group)}]}
                          {:type :reg-objects
                           :page-id page-id
                           :shapes [(:id group)]}]]

            (rx/of (dch/commit-changes {:redo-changes rchanges
                                        :undo-changes uchanges
                                        :origin it})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))


