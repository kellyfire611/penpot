;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.export
  (:require
   [app.main.render :as r]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [app.util.zip :as uz]
   [app.util.json :as json]
   [app.worker.impl :as impl]
   [beicon.core :as rx]))

(defn create-manifest
  "Creates a manifest entry for the given files"
  [team-id files]
  (letfn [(format-page [manifest page]
            (-> manifest
                (assoc (str (:id page))
                       {:name (:name page)})))

          (format-file [manifest file]
            (let [name  (:name file)
                  pages (->> (get-in file [:data :pages]) (mapv str))
                  index (->> (get-in file [:data :pages-index]) (vals)
                             (reduce format-page {}))]
              (-> manifest
                  (assoc (str (:id file))
                         {:name       name
                          :pages      pages
                          :pagesIndex index}))))]
    (let [manifest {:teamId (str team-id)
                    :files (->> (vals files) (reduce format-file {}))}]
      (json/encode manifest))))

(defn get-page-data
  [{file-id :file-id {:keys [id name] :as data} :data}]
  (->> (r/render-page data)
       (rx/map (fn [markup]
                 {:id id
                  :name name
                  :file-id file-id
                  :markup markup}))))

(defn process-pages [file]
  (let [pages (get-in file [:data :pages])
        pages-index (get-in file [:data :pages-index])]
    (->> pages
         (map #(hash-map
                :file-id (:id file)
                :data (get pages-index %))))))

(defn collect-page
  [{:keys [id file-id markup] :as page}]
  [(str file-id "/" id ".svg") markup])

(defmethod impl/handler :export-file
  [{:keys [team-id project-id files] :as message}]

  (let [files-ids (->> files (mapv :id))

        files-stream
        (->> (rx/from files-ids)
             (rx/merge-map #(rp/query :file {:id %}))
             (rx/reduce #(assoc %1 (:id %2) %2) {})
             (rx/share))

        manifest-stream
        (->> files-stream
             (rx/map #(create-manifest team-id %))
             (rx/map #(vector "manifest.json" %)))

        render-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/flat-map process-pages)
             (rx/observe-on :async)
             (rx/flat-map get-page-data)
             (rx/share))

        pages-stream
        (->> render-stream
             (rx/map collect-page))]

    (rx/merge
     (->> render-stream
          (rx/map #(hash-map :type :progress
                             :data (str "Render " (:file-name %) " - " (:name %)))))
     (->> (rx/merge pages-stream
                    manifest-stream)
          (rx/reduce conj [])
          (rx/flat-map uz/compress-files)
          (rx/map #(hash-map :type :finish
                             :data (dom/create-uri %)))))))
