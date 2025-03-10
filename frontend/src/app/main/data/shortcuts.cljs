;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.shortcuts
  (:refer-clojure :exclude [meta reset!])
  (:require
   ["mousetrap" :as mousetrap]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.logging :as log]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(log/set-level! :warn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mac-command "\u2318")
(def mac-option  "\u2325")
(def mac-delete  "\u232B")
(def mac-shift   "\u21E7")
(def mac-control "\u2303")
(def mac-esc     "\u238B")
(def mac-enter   "\u23CE")

(def left-arrow  "\u2190")
(def up-arrow    "\u2191")
(def right-arrow "\u2192")
(def down-arrow  "\u2193")

(defn c-mod
  "Adds the control/command modifier to a shortcuts depending on the
  operating system for the user"
  [shortcut]
  (if (cfg/check-platform? :macos)
    (str "command+" shortcut)
    (str "ctrl+" shortcut)))

(defn a-mod
  "Adds the alt/option modifier to a shortcuts depending on the
  operating system for the user"
  [shortcut]
  (str "alt+" shortcut))

(defn ca-mod
  [shortcut]
  (c-mod (a-mod shortcut)))

(defn meta
  [key]
  ;; If the key is "+" we need to surround with quotes
  ;; otherwise will not be very readable
  (let [key (if (and (not (cfg/check-platform? :macos))
                     (= key "+"))
              "\"+\""
              key)]
    (str
     (if (cfg/check-platform? :macos)
       mac-command
       "Ctrl+")
     key)))

(defn shift
  [key]
  (str
   (if (cfg/check-platform? :macos)
     mac-shift
     "Shift+")
   key))

(defn alt
  [key]
  (str
   (if (cfg/check-platform? :macos)
     mac-option
     "Alt+")
   key))

(defn meta-shift
  [key]
  (-> key meta shift))

(defn meta-alt
  [key]
  (-> key meta alt))

(defn supr
  []
  (if (cfg/check-platform? :macos)
    mac-delete
    "Supr"))

(defn esc
  []
  (if (cfg/check-platform? :macos)
    mac-esc
    "Escape"))

(defn enter
  []
  (if (cfg/check-platform? :macos)
    mac-enter
    "Enter"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- EVENT: push

(s/def ::tooltip ::us/string)
(s/def ::fn fn?)

(s/def ::command
  (s/or :str ::us/string
        :vec vector?))

(s/def ::shortcut
  (s/keys :req-un [::command]
          :opt-un [::fn
                   ::tooltip]))

(s/def ::shortcuts
  (s/map-of ::us/keyword
            ::shortcut))

(defn- wrap-cb
  [key cb]
  (fn [event]
    (log/debug :msg (str "Shortcut" key))
    (.preventDefault event)
    (cb event)))

(defn- bind!
  [shortcuts]
  (->> shortcuts
       (remove #(:disabled (second %)))
       (run! (fn [[key {:keys [command fn type]}]]
               (if (vector? command)
                 (run! #(mousetrap/bind % (wrap-cb key fn) type) command)
                 (mousetrap/bind command (wrap-cb key fn) type))))))

(defn- reset!
  ([]
   (mousetrap/reset))
  ([shortcuts]
   (mousetrap/reset)
   (bind! shortcuts)))

(defn push-shortcuts
  [key shortcuts]
  (us/assert ::us/keyword key)
  (us/assert ::shortcuts shortcuts)
  (ptk/reify ::push-shortcuts
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :shortcuts (fnil conj '()) [key shortcuts])))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [[key shortcuts] (peek (:shortcuts state))]
        (reset! shortcuts)))))

(defn pop-shortcuts
  [key]
  (ptk/reify ::pop-shortcuts
    ptk/UpdateEvent
    (update [_ state]
      (update state :shortcuts (fn [shortcuts]
                                 (let [current-key (first (peek shortcuts))]
                                   (if (= key current-key)
                                     (pop shortcuts)
                                     shortcuts)))))
    ptk/EffectEvent
    (effect [_ state stream]
      (let [[key* shortcuts] (peek (:shortcuts state))]
        (when (not= key key*)
          (reset! shortcuts))))))
