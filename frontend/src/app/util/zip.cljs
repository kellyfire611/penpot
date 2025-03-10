;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.zip
  "Helpers for make zip file (using jszip)."
  (:require
   ["jszip" :as zip]
   [app.common.data :as d]
   [beicon.core :as rx]
   [promesa.core :as p]
   [app.util.http :as http]))

(defn compress-files
  [files]
  (letfn [(attach-file [zobj [name content]]
            (.file zobj name content))]
    (let [zobj (zip.)]
      (run! (partial attach-file zobj) files)
      (->> (.generateAsync zobj #js {:type "blob"})
           (rx/from)))))

(defn load-from-url
  "Loads the data from a blob url"
  [url]
  (->> (http/send!
        {:uri url
         :response-type :blob
         :method :get})
       (rx/map :body)
       (rx/flat-map zip/loadAsync)))

(defn- process-file [entry path]
  (cond
    (nil? entry)
    (p/rejected "No file found")

    (.-dir entry)
    (p/resolved {:dir path})

    :else
    (-> (.async entry "text")
        (p/then #(hash-map :path path :content %)))))

(defn get-file
  "Gets a single file from the zip archive"
  [zip path]
  (-> (.file zip path)
      (process-file path)
      (rx/from)))

(defn extract-files
  "Creates a stream that will emit values for every file in the zip"
  [zip]
  (let [promises (atom [])
        get-file
        (fn [path entry]
          (let [current (process-file entry path)]
            (swap! promises conj current)))]
    (.forEach zip get-file)

    (->> (rx/from (p/all @promises))
         (rx/flat-map identity))))
