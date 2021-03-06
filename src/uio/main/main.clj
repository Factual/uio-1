(ns uio.main.main
  (:require [uio.uio :as uio]
            [uio.impl :refer [die] :as impl]
            [clojure.string :as str]
            [clojure.java.io :as jio]
            [clojure.tools.cli :refer [parse-opts]])
  (:import [org.apache.log4j Level Logger ConsoleAppender PatternLayout]
           [java.text SimpleDateFormat]
           [java.util TimeZone])
  (:gen-class))

(defn err [& msg]
  (binding [*out* *err*]
    (apply print msg)
    (flush)))

(defn errln [& msg]
  (binding [*out* *err*]
    (apply println msg)))

(def exit-1 "exit-1")

(def default-config-url "res:///uio/main/default_config.clj")

(defn load-config [url]
  (->> (clojure.edn/read-string (slurp (uio/from url)))
       (into (array-map))))

(defn home-config-url []
  (let [home           (System/getProperty "user.home")     ; e.g. "/Users/joe"
        _              (if (str/blank? home)
                         (die "Couldn't get user home directory"))
        config-dir-url (uio/with-parent (impl/replace-path "file:///" home) ".uio")
        config-url     (uio/with-parent config-dir-url "config.clj")]
    config-url))

(defn load-or-save-home-config->url []
  (let [config-url (home-config-url)]
    (uio/mkdir (uio/parent-of config-url))
    (uio/attrs (uio/parent-of config-url) {:perms "rwx------"})
    (when-not (uio/exists? config-url)
      (uio/copy default-config-url config-url))
    (uio/attrs config-url {:perms "rw-------"})
    config-url))

(defn load-s3cfg []
  (let [s3cfg-url (uio/with-parent
                    (impl/replace-path "file:///" (System/getProperty "user.home"))
                    ".s3cfg")]
    (->> (str/split (slurp (uio/from s3cfg-url)) #"\n")
         (remove #(#{\# \;} (first %)))
         (map #(let [[k v] (str/split % #"\s?=\s?" 2)]      ; TODO respect comments. Use clojure-ini?
                 [(keyword k) v]))
         (into {})
         ((fn [m]
            {"s3://" {:access (or (:access_key m) (die "Can't find :access_key in ~/.s3cfg. Skipping"))
                      :secret (or (:secret_key m) (die "Can't find :secret_key in ~/.s3cfg. Skipping"))}})))))

(def ymd-hm-utc (doto (SimpleDateFormat. "yyyy-MM-dd hh:mm")
                      (.setTimeZone (TimeZone/getTimeZone "UTC"))))

(defn size->human-size [n]
  (loop [n     (/ n 1)
         units "BKMGTPEZY"]
    (if (or (<= n 999.9)
            (not (second units)))
      (str (if (or (not (ratio? n))
                   (zero? (rem (numerator n)
                               (denominator n))))
             n
             (format "%.1f" (float n)))
           (first units))

      (recur (/ n 1024)
             (rest units)))))

(defn s-if-plural [n]
  (if (or (= n 11)
          (not= 1 (rem n 10)))
    "s"
    ""))

(defn op-or-alias->op [op]
  (or ({"cat"  "from"
        "cat*" "from*"
        "rm"   "delete"
        "cp"   "copy"} op)
      op))

(defn get-version []
  (str "["
       (try (->> (uio/from "res:///META-INF/leiningen/uio/uio/project.clj")
                 slurp
                 (re-find #"^\(defproject (.+\")")
                 second)
            (catch Throwable e (str "unknown: " e)))
       "]"))

(def help-hint "To see examples, run `uio --help`.")

(defn print-usage []
  (println "Usage: cat file.txt | uio to       fs:///path/to/file.txt")
  (println "       cat file.txt | uio to*      fs:///path/to/file.txt.gz")
  (println)
  (println "                      uio from     fs:///path/to/file.txt    > file.txt")
  (println "                      uio from*    fs:///path/to/file.txt.gz > file.txt")
  (println)
  (println "                      uio size     fs:///path/to/file.txt")
  (println "                      uio exists?  fs:///path/to/file.txt")
  (println "                      uio delete   fs:///path/to/file.txt")
  (println "                      uio mkdir    fs:///path/to/dir/")
  (println)
  (println "                      uio copy     fs:///source/path/to/file.txt fs:///destination/path/to/file.txt")
  (println)
  (println "                      uio ls [-lh] fs:///path/to/dir/")
  (println "                              -l - list in long format (show attributes)")
  (println "                              -h - print sizes in human readable format")
  (println)
  (println "                      uio --help - print this help")
  (println)
  (println "Common flags:                 -v - print stack traces and annoying logs to stderr")
  (println)
  (println "Experimental (will change in future!):")
  (println "                      uio ls [-rs] fs:///path/to/dir/")
  (println "                              -r - list files and directories recursively")
  (println "                              -s - print total file size, file and directory count")
  (println)
  (println)
  (println "Version:" (get-version))
  (println "FS:     " (str/join " " (map name (:fs     (impl/list-available-implementations)))))
  (println "Codecs: " (str/join " " (map name (:codecs (impl/list-available-implementations)))))
  (println "Config: " (home-config-url)))

(defn run [[op a b :as args]
           {:keys [recurse
                   attrs
                   human-readable] :as opts}]

  ; TODO validate arg count
  ; TODO validate 1st and 2args are urls

  (case (op-or-alias->op op)
    "help"    (print-usage)

    "from"    (with-open [is (uio/from  a)] (jio/copy is System/out :buffer-size 8192))
    "from*"   (with-open [is (uio/from* a)] (jio/copy is System/out :buffer-size 8192))

    "to"      (with-open [os (uio/to    a)] (jio/copy System/in os  :buffer-size 8192))
    "to*"     (with-open [os (uio/to*   a)] (jio/copy System/in os  :buffer-size 8192))

    "size"    (println (uio/size a))
    "exists?" (if-not (uio/exists? a) (die exit-1))

    "delete"  (uio/delete a)

    "mkdir"   (uio/mkdir a)

    ; TODO take a sample of first 32 and calculate max, keep rolling, change the pattern if numbers grow
    ; TODO remove columns completely if there are no values for that column?
    "ls"      (->> (uio/ls a {:recurse recurse
                              :attrs   attrs})
                   (reduce
                     (fn [stats f]
                       (let [url (cond-> (:url f)
                                         (:symlink f) (str " -> " (:symlink f))
                                         (:error f)   (str " -- " (:error f)))]

                         (println (if attrs
                                    (format (if human-readable
                                              "%9s %-10s %-10s %16s %6s %s"
                                              "%9s %-10s %-10s %16s %11s %s")
                                            (or (:perms f) "")
                                            (or (:owner f) "")
                                            (or (:group f) "")
                                            (or (some->> (:modified f)
                                                         (.format ymd-hm-utc))
                                                "")
                                            (or (and (:size f) ; TODO refactor
                                                     (cond-> (:size f)
                                                             human-readable (size->human-size)))
                                                "")
                                            url)
                                    url))

                         (cond-> stats
                                 (:dir f)  (update :dirs + 1)
                                 (:size f) (-> (update :files + 1)
                                               (update :size + (:size f))))))

                     {:size 0 :files 0 :dirs 0})

                   (#(when (:summarize opts)
                       (println (str (if human-readable
                                       (size->human-size (:size %))
                                       (str (:size %) " byte" (s-if-plural (:size %))))
                                     ", " (:files %) " file" (s-if-plural (:files %))
                                     ", " (:dirs %)  " dir"  (s-if-plural (:dirs %)))))))

    "copy"    (uio/copy a b) ; TODO check url-b

    "_export" (->> impl/*config*
                   (map (fn [[url m]]
                          (str url
                               (if (seq m)
                                 (str "?"
                                      (str/join "&"
                                                (for [[k v] m]
                                                  (str (uio/escape-url (name k)) "=" (uio/escape-url v)))))))))
                   sort
                   (run! println))

    (do (errln (if op
                 (str "Unknown command: " op)
                 "Expected a command, but got none."))
        (errln help-hint)
        (die exit-1))))

; TODO ensure `url` and `url-b` are URLs
; TODO .         -> file://<cwd>/
; TODO file.txt  -> file://<cwd>/file.txt
; TODO /file.txt -> file:///file.txt
;
; TODO?    (copy "file.txt" "hdfs:///path/to/")
; TODO? => (copy "file.txt" "hdfs:///path/to/file.txt")
;
; TODO fix S3: s3cmd ls s3://geopulse-ingest/teamcity/build_383/00/
;
(defn -main [& args]
  (let [cli (parse-opts args
                        [["-r" "--recurse"        "Make `ls` recursive"                                     :default false]
                         ["-l" "--attrs"          "Make `ls` list in long format (show attributes)"         :default false]
                         ["-s" "--summarize"      "Make `ls` print total file size, file and dir count"     :default false]
                         ["-h" "--human-readable" "Print sizes in human readable format (e.g., 1K 234M 2G)" :default false]
                         ["-v" "--verbose"        "Print stack traces"                                      :default false]
                         [nil  "--help"           "Show help"                                               :default false]])]

    (if (-> cli :options :verbose)
      (.addAppender (Logger/getRootLogger)                  ; 
                    (doto (ConsoleAppender.)
                          (.setTarget "System.err")
                          (.setLayout (PatternLayout. "%d [%p|%c|%C{1}] %m%n"))
                          (.setThreshold Level/WARN)
                          (.activateOptions)))
      (.setLevel (Logger/getRoot) Level/OFF))               ; mute HDFS logging by default

    (if (-> cli :options :help)
      (print-usage)
      (try
        (when (:errors cli)
          (doseq [e (:errors cli)]
            (errln e))
          (errln help-hint)
          (die exit-1))

        (uio/with (->> (or (try (load-config (load-or-save-home-config->url)) ; config in ~
                                (catch Exception e (when (-> cli :options :verbose) (errln "Couldn't load or save home config, skipping:" e))))

                           (try (load-config default-config-url) ; bundled config
                                (catch Exception e (when (-> cli :options :verbose) (errln "Couldn't load default config, skipping:" e))))

                           {})                              ; empty config

                       ; override with values ~/.s3cfg when {"s3://" {}}
                       (#(merge %
                                (when (= {} (get % "s3://"))
                                  (try (load-s3cfg)
                                       (catch Exception e (when (-> cli :options :verbose)
                                                            (errln "Couldn't load ~/.s3cfg, skipping:" e))))))))
          (run (:arguments cli)
               (:options cli)))

        (catch Throwable e (when-not (= exit-1 (.getMessage e))
                             (errln (str e))
                             (when (-> cli :options :verbose)
                               (errln)
                               (errln e)))
                           (System/exit 1))

        (finally (System/exit 0))))))
