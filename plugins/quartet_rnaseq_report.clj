(ns quartet-rnaseq-report
  (:require [clojure.core.async :as async]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [tservice.lib.filter-files :as ff]
            [tservice.vendor.multiqc :as mq]
            [tservice.events :as events]
            [clojure.data.json :as json]))

;;; ------------------------------------------------ Event Metadata --------------------------------------------------
(def metadata
  {:route ["/ballgown2exp"
           {:post {:summary "Convert ballgown files to experiment table and generate report."
                   :parameters {:body specs/ballgown2exp-params-body}
                   :responses {201 {:body {:download_url string? :log_url string?}}}
                   :handler (fn [{{{:keys [filepath phenotype]} :body} :parameters}]
                              (let [workdir (get-workdir)
                                    from-path (u/replace-path filepath workdir)
                                    relative-dir (fs-lib/join-paths "download" (u/uuid))
                                    to-dir (fs-lib/join-paths workdir relative-dir)
                                    phenotype-filepath (fs-lib/join-paths to-dir "phenotype.txt")
                                    phenotype-data (cons ["sample_id" "group"]
                                                         (map vector (:sample_id phenotype) (:group phenotype)))
                                    log-path (fs-lib/join-paths relative-dir "log")]
                                (log/info phenotype phenotype-data)
                                (fs-lib/create-directories! to-dir)
                                (with-open [file (io/writer phenotype-filepath)]
                                  (csv/write-csv file phenotype-data :separator \tab))
                                ; Launch the ballgown2exp
                                (spit log-path (json/write-str {:status "Running" :msg ""}))
                                (events/publish-event! :ballgown2exp-convert
                                                       {:ballgown-dir from-path
                                                        :phenotype-filepath phenotype-filepath
                                                        :dest-dir to-dir})
                                {:status 201
                                 :body {:download_url (fs-lib/join-paths relative-dir)
                                        :report (fs-lib/join-paths relative-dir "multiqc.html")
                                        :log_url log-path}}))}}]
   :manifest {}
   :schema {}})

(def ^:const quartet-rnaseq-report-topics
  "The `Set` of event topics which are subscribed to for use in quartet-rnaseq-report tracking."
  #{:quartet_rnaseq_report-convert})

(def ^:private quartet-rnaseq-report-channel
  "Channel for receiving event quartet-rnaseq-report we want to subscribe to for quartet-rnaseq-report events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- quartet-rnaseq-report!
  "Chaining Pipeline: merge_exp_file -> r2r -> multiqc."
  [ballgown-dir phenotype-filepath dest-dir]
  (let [files (ff/batch-filter-files ballgown-dir [".*call-ballgown/.*.txt"])
        ballgown-dir (fs-lib/join-paths dest-dir "ballgown")
        exp-filepath (fs-lib/join-paths dest-dir "fpkm.txt")
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")]
    (try
      (fs-lib/create-directories! ballgown-dir)
      (fs-lib/create-directories! result-dir)
      (log/info "Merge these files: " files)
      (log/info "Merge gene experiment files from ballgown directory to a experiment table: " ballgown-dir exp-filepath)
      (ff/copy-files! files ballgown-dir {:replace-existing true})
      (me/merge-exp-files! (ff/list-files ballgown-dir {:mode "file"}) exp-filepath)
      (log/info "Call R2R: " exp-filepath phenotype-filepath result-dir)
      (let [r2r-result (r2r/call-r2r! exp-filepath phenotype-filepath result-dir)
            multiqc-result (when (= (:status r2r-result) "Success")
                             (mq/multiqc result-dir dest-dir {:title "RNA-seq Report"}))
            result (if multiqc-result (assoc r2r-result
                                             :status (:status multiqc-result)
                                             :msg (str (:msg r2r-result) "\n" (:msg multiqc-result)))
                       r2r-result)
            log (json/write-str result)]
        (log/info "Status: " result)
        (spit log-path log))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-quartet-rnaseq-report-event!
  "Handle processing for a single event notification received on the quartet-rnaseq-report-channel"
  [quartet-rnaseq-report-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} quartet-rnaseq-report-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "quartet-rnaseq-report"  (quartet-rnaseq-report! (:ballgown-dir object) (:phenotype-filepath object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process quartet-rnaseq-report event. %s" (:topic quartet-rnaseq-report-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for quartet-rnaseq-report events."
  []
  (events/start-event-listener! quartet-rnaseq-report-topics quartet-rnaseq-report-channel process-quartet-rnaseq-report-event!))
