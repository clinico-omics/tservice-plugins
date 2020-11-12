(ns plugins.reports.quartet-rnaseq-report
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [plugins.libs.commons :as comm]
            [plugins.wrappers.exp2qcdt :as exp2qcdt]
            [plugins.wrappers.merge-exp :as me]
            [spec-tools.core :as st]
            [spec-tools.json-schema :as json-schema]
            [tservice.config :refer [get-workdir env]]
            [tservice.events :as events]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.fs :as fs-lib]
            [tservice.util :as u]
            [tservice.vendor.multiqc :as mq]))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^[a-zA-Z0-9]+:\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(s/def ::group
  (st/spec
   {:spec                string?
    :type                :string
    :description         "A group name which is matched with library."
    :swagger/default     []
    :reason              "The group must a string."}))

(s/def ::library
  (st/spec
   {:spec                string?
    :type                :string
    :description         "A library name."
    :swagger/default     []
    :reason              "The library must a string."}))

(s/def ::sample
  (st/spec
   {:spec                string?
    :type                :string
    :description         "A sample name."
    :swagger/default     []
    :reason              "The sample name must a string."}))

(s/def ::metadat-item
  (s/keys :req-un [::library
                   ::group
                   ::sample]))

(s/def ::metadata
  (s/coll-of ::metadat-item))

(s/def ::lab
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Lab name."
    :swagger/default     []
    :reason              "The lab_name must be string."}))

(s/def ::sequencing_platform
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Sequencing Platform."
    :swagger/default     []
    :reason              "The sequencing_platform must be string."}))

(s/def ::sequencing_method
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Sequencing Method"
    :swagger/default     []
    :reason              "The sequencing_method must be string."}))

(s/def ::library_protocol
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Library protocol."
    :swagger/default     []
    :reason              "The library_protocol must be string."}))

(s/def ::library_kit
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Library kit."
    :swagger/default     []
    :reason              "The library_kit must be string."}))

(s/def ::read_length
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Read length"
    :swagger/default     []
    :reason              "The read_length must be string."}))

(s/def ::date
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Date"
    :swagger/default     []
    :reason              "The date must be string."}))

(s/def ::parameters
  (s/keys :req-un [::lab
                   ::sequencing_platform
                   ::sequencing_method
                   ::library_protocol
                   ::library_kit
                   ::read_length
                   ::date]))

(def quartet-rna-report-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath ::metadata ::parameters]))

;;; ------------------------------------------------ Event Metadata ------------------------------------------------
(def metadata
  {:route    ["/report/quartet-rnaseq-report"
              {:tags ["Report"]
               :post {:summary "Parse the results of the quartet-rnaseq-qc app and generate the report."
                      :parameters {:body quartet-rna-report-params-body}
                      :responses {201 {:body {:results string? :log string? :report string? :id string?}}}
                      :handler (fn [{{{:keys [filepath metadata parameters]} :body} :parameters}]
                                 (let [workdir (get-workdir)
                                       from-path (u/replace-path filepath workdir)
                                       uuid (u/uuid)
                                       relative-dir (fs-lib/join-paths "download" uuid)
                                       to-dir (fs-lib/join-paths workdir relative-dir)
                                       log-path (fs-lib/join-paths to-dir "log")]
                                   (fs-lib/create-directories! to-dir)
                                   (spit log-path (json/write-str {:status "Running" :msg ""}))
                                   (events/publish-event! :quartet_rnaseq_report-convert
                                                          {:datadir from-path
                                                           :parameters parameters
                                                           :metadata metadata
                                                           :dest-dir to-dir})
                                   {:status 201
                                    :body {:results (fs-lib/join-paths relative-dir)
                                           :report (fs-lib/join-paths relative-dir "multiqc.html")
                                           :log (fs-lib/join-paths relative-dir "log")
                                           :id uuid}}))}
               :get {:summary "A json shema for quartet-rnaseq-report."
                     :parameters {}
                     :responses {200 {:body map?}}
                     :handler (fn [_]
                                {:status 200
                                 :body (json-schema/transform quartet-rna-report-params-body)})}}]
   :manifest {:description "Parse the results of the quartet-rna-qc app and generate the report."
              :category "Report"
              :home "https://github.com/clinico-omics/quartet-rnaseq-report"
              :name "Quartet RNA-Seq Report"
              :source "PGx"
              :short_name "quartet-rnaseq-report"
              :icons [{:src "", :type "image/png", :sizes "192x192"}
                      {:src "", :type "image/png", :sizes "192x192"}]
              :author "Jun Shang"
              :hidden false
              :id "f65d87fd3dd2213d91bb15900ba57c11"
              :app_name "shangjun/quartet-rnaseq-report"}})

(def ^:const quartet-rnaseq-report-topics
  "The `Set` of event topics which are subscribed to for use in quartet-rnaseq-report tracking."
  #{:quartet_rnaseq_report-convert})

(def ^:private quartet-rnaseq-report-channel
  "Channel for receiving event quartet-rnaseq-report we want to subscribe to for quartet-rnaseq-report events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- quartet-rnaseq-report!
  "Chaining Pipeline: filter-files -> copy-files -> merge_exp_file -> exp2qcdt -> multiqc."
  [datadir parameters metadata dest-dir]
  (log/info "Generate quartet rnaseq report: " datadir parameters metadata dest-dir)
  (let [metadata-file (fs-lib/join-paths dest-dir
                                         "results"
                                         "metadata.csv")
        parameters-file (fs-lib/join-paths dest-dir
                                           "results"
                                           "general-info.json")
        files (ff/batch-filter-files datadir [".*call-ballgown/.*.txt"])
        ballgown-dir (fs-lib/join-paths dest-dir "ballgown")
        exp-filepath (fs-lib/join-paths dest-dir "fpkm.txt")
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")
        config (fs-lib/join-paths (:tservice-plugin-path env) "config/quartet_rnaseq_report.yaml")]
    (try
      (fs-lib/create-directories! ballgown-dir)
      (fs-lib/create-directories! result-dir)
      (log/info "Merge these files: " files)
      (log/info "Merge gene experiment files from ballgown directory to a experiment table: " ballgown-dir exp-filepath)
      (ff/copy-files! files ballgown-dir {:replace-existing true})
      (me/merge-exp-files! (ff/list-files ballgown-dir {:mode "file"}) exp-filepath)
      (spit parameters-file (json/write-str parameters))
      (comm/write-csv! metadata-file metadata)
      (let [exp2qcdt-result (exp2qcdt/call-exp2qcdt! exp-filepath metadata-file result-dir)
            multiqc-result (when (= (:status exp2qcdt-result) "Success")
                             (mq/multiqc result-dir dest-dir {:config config :template "quartet_rnaseq_report"}))
            result {:status (:status multiqc-result)
                    :msg (:msg multiqc-result)}
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
        "quartet_rnaseq_report" (quartet-rnaseq-report! (:datadir object) (:parameters object) (:metadata object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process quartet-rnaseq-report event. %s" (:topic quartet-rnaseq-report-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for quartet-rnaseq-report events."
  []
  (events/start-event-listener! quartet-rnaseq-report-topics quartet-rnaseq-report-channel process-quartet-rnaseq-report-event!))