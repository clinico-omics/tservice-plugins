(ns plugins.tools.merge-rnaseq-exp
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [spec-tools.core :as st]
            [spec-tools.json-schema :as json-schema]
            [tservice.lib.filter-files :as ff]
            [tservice.config :refer [get-workdir env]]
            [tservice.events :as events]
            [plugins.wrappers.merge-exp :as me]
            [tservice.lib.fs :as fs-lib]
            [tservice.vendor.multiqc :as mq]
            [tservice.util :as u]
            [clojure.java.shell :as shell :refer [sh]]
            [plugins.libs.commons :refer [get-path-variable correct-filepath]]))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(s/def ::enable_multiqc
  (st/spec
   {:spec                boolean?
    :type                :boolean
    :description         "Whether enable to generate multiqc report."
    :swagger/default     true
    :reason              "Only support bollean."}))

(s/def ::excludes
  (st/spec
   {:spec                vector?
    :type                :vector
    :description         "A collection of exclude files."
    :swagger/default     true
    :reason              "The excludes must be an array."}))

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^[a-zA-Z0-9]+:\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(def merge-exp-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath]
          :opt-un [::excludes ::enable_multiqc]))

;;; ------------------------------------------------ Event Metadata -------------------------------------------------

(def metadata
  {:route ["/tool/merge-rnaseq-exp"
           {:tags ["Tool"]
            :post {:summary "Merge expression table for rna-seq."
                   :parameters {:body merge-exp-params-body}
                   :responses {201 {:body {:output_file string? :log_url string?}}}
                   :handler (fn [{{{:keys [filepath excludes enable_multiqc]} :body} :parameters}]
                              (let [workdir (get-workdir)
                                    from-path (correct-filepath filepath)
                                    uuid (u/uuid)
                                    relative-dir (fs-lib/join-paths "download" uuid)
                                    output-file (fs-lib/join-paths relative-dir "fpkm.csv")
                                    report-file (fs-lib/join-paths relative-dir "multiqc_report.html")
                                    log-path (fs-lib/join-paths relative-dir "log")
                                    to-dir (fs-lib/join-paths workdir relative-dir)]
                                (fs-lib/create-directories! to-dir)
                                ; Launch the mergeexp
                                (spit (fs-lib/join-paths workdir log-path) (json/write-str {:status "Running" :msg ""}))
                                (events/publish-event! :mergeexp-convert
                                                       {:data-dir from-path
                                                        :excludes excludes
                                                        :enable-multiqc enable_multiqc
                                                        :dest-dir to-dir})
                                {:status 201
                                 :body {:output_file output-file
                                        :report report-file
                                        :log_url log-path}}))}
            :get {:summary "A json shema for merge-exp."
                  :parameters {}
                  :responses {200 {:body map?}}
                  :handler (fn [_]
                             {:status 200
                              :body (json-schema/transform merge-exp-params-body)})}}]
   :manifest {:description "Merge expression table for rna-seq."
              :category "Tool"
              :home "https://github.com/clinico-omics/tservice-plugins"
              :name "Merge Expression Table fro RNA-Seq"
              :source "PGx"
              :short_name "merge-rnaseq-exp"
              :icons [{:src "", :type "image/png", :sizes "192x192"}
                      {:src "", :type "image/png", :sizes "192x192"}]
              :author "Jingcheng Yang"
              :hidden false
              :id "79e4498790428b2821d2abab53e4af7d"
              :app_name "jingchengyang/merge-rnaseq-exp"}})

(def ^:const mergeexp-topics
  "The `Set` of event topics which are subscribed to for use in mergeexp tracking."
  #{:mergeexp-convert})

(def ^:private mergeexp-channel
  "Channel for receiving event mergeexp we want to subscribe to for mergeexp events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------
(defn- decompression-tar
  [filepath]
  (shell/with-sh-env {:PATH   (get-path-variable)
                      :LC_ALL "en_US.utf-8"
                      :LANG   "en_US.utf-8"}
    (let [command ["bash" "-c"
                   (format "tar -xvf %s -C %s" filepath (fs-lib/parent-path filepath))]
          result  (apply sh command)
          status (if (= (:exit result) 0) "Success" "Error")
          msg (str (:out result) "\n" (:err result))]
      {:status status
       :msg msg})))

(defn- mergeexp!
  [data-dir dest-dir excludes enable-multiqc]
  ;; TODO: filter excludes?
  (let [files (ff/batch-filter-files data-dir [".*call-ballgown/.*.txt"])
        ballgown-dir (fs-lib/join-paths dest-dir "ballgown")
        log-path (fs-lib/join-paths dest-dir "log")
        output-file (fs-lib/join-paths dest-dir "fpkm.csv")]
    (try
      (fs-lib/create-directories! ballgown-dir)
      (log/info "Merge these files: " files)
      (log/info "Merge gene experiment files from ballgown directory to a experiment table: " ballgown-dir output-file)
      (ff/copy-files! files ballgown-dir {:replace-existing true})
      (me/merge-exp-files! (ff/list-files ballgown-dir {:mode "file"}) output-file)
      (if enable-multiqc
        (let [files (ff/batch-filter-files data-dir [".*call-fastqc/.*.zip"
                                                     ".*call-fastqscreen/.*screen.txt"
                                                     ".*call-qualimap/.*bamqc"
                                                     ".*call-qualimap/.*bamqc_qualimap.tar.gz"
                                                     ".*call-qualimap/.*RNAseq"
                                                     ".*call-qualimap/.*RNAseq_qualimap.tar.gz"])
              multiqc-dir (fs-lib/join-paths dest-dir "multiqc")
              config (fs-lib/join-paths (:tservice-plugin-path env) "plugins/config/multiqc_report.yaml")]
          (fs-lib/create-directories! multiqc-dir)
          (ff/copy-files! files multiqc-dir {:replace-existing true})
          (doseq [file (ff/batch-filter-files multiqc-dir [".*bamqc_qualimap.tar.gz" ".*RNAseq_qualimap.tar.gz"])]
            (decompression-tar file))
          (let [multiqc-result (mq/multiqc multiqc-dir dest-dir {:title "MultiQC Report" :template "default" :config config})
                result {:status (:status multiqc-result)
                        :msg (:msg multiqc-result)}
                log (json/write-str result)]
            (log/info "Status: " result)
            (spit log-path log)))
        (spit log-path (json/write-str {:status "Success" :msg ""})))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-mergeexp-event!
  "Handle processing for a single event notification received on the mergeexp-channel"
  [mergeexp-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} mergeexp-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "mergeexp" (mergeexp! (:data-dir object) (:dest-dir object) (:excludes object) (:enable-multiqc object))))
    (catch Throwable e
      (log/warn (format "Failed to process mergeexp event. %s" (:topic mergeexp-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for mergeexp events."
  []
  (events/start-event-listener! mergeexp-topics mergeexp-channel process-mergeexp-event!))
