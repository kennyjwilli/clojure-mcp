(ns clojure-mcp.main
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure-mcp.core :as core]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.prompts :as prompts]
            [clojure-mcp.tools.project.core :as project]
            [clojure-mcp.resources :as resources]
            [clojure-mcp.config :as config]
            ;; tools
            [clojure-mcp.tools.directory-tree.tool :as directory-tree-tool]
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
            [clojure-mcp.tools.grep.tool :as new-grep-tool]
            [clojure-mcp.tools.glob-files.tool :as glob-files-tool]
            [clojure-mcp.tools.think.tool :as think-tool]
            [clojure-mcp.tools.bash.tool :as bash-tool]
            [clojure-mcp.tools.form-edit.combined-edit-tool :as combined-edit-tool]
            [clojure-mcp.tools.form-edit.tool :as new-form-edit-tool]
            [clojure-mcp.tools.file-edit.tool :as file-edit-tool]
            [clojure-mcp.tools.file-write.tool :as file-write-tool]
            [clojure-mcp.tools.dispatch-agent.tool :as dispatch-agent-tool]
            [clojure-mcp.tools.architect.tool :as architect-tool]
            [clojure-mcp.tools.code-critique.tool :as code-critique-tool]
            [clojure-mcp.tools.project.tool :as project-tool]
            [clojure-mcp.tools.scratch-pad.tool :as scratch-pad-tool]))

;; Define the resources you want available
(defn my-resources [nrepl-client-map working-dir]
  (keep
   identity
   [(resources/create-file-resource
     "custom://project-summary"
     "PROJECT_SUMMARY.md"
     "A Clojure project summary document for the project hosting the REPL, this is intended to provide the LLM with important context to start."
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "PROJECT_SUMMARY.md")))
    (resources/create-file-resource
     "custom://readme"
     "README.md"
     "A README document for the current Clojure project hosting the REPL"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "README.md")))
    (resources/create-file-resource
     "custom://claude"
     "CLAUDE.md"
     "The Claude instructions document for the current project hosting the REPL"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "CLAUDE.md")))
    (resources/create-file-resource
     "custom://llm-code-style"
     "LLM_CODE_STYLE.md"
     "Guidelines for writing Clojure code for the current project hosting the REPL"
     "text/markdown"
     (str working-dir "/LLM_CODE_STYLE.md"))
    (let [{:keys [outputs error]} (project/inspect-project nrepl-client-map)]
      (when-not error
        (resources/create-string-resource
         "custom://project-info"
         "Clojure Project Info"
         "Information about the current Clojure project structure, attached REPL environment and dependencies"
         "text/markdown"
         outputs)))]))

(declare code-review-prompt-example)

(defn my-prompts [working-dir]
  [{:name "clojure_repl_system_prompt"
    :description "Provides instructions and guidelines for Clojure development, including style and best practices."
    :arguments [] ;; No arguments needed for this prompt
    :prompt-fn (prompts/simple-content-prompt-fn
                "System Prompt: Clojure REPL"
                (str
                 (prompts/load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl_form_edit.md")
                 (prompts/load-prompt-from-resource "clojure-mcp/prompts/system/clojure_form_edit.md")))}
   (prompts/create-project-summary working-dir)
   #_prompts/scratch-pad-guide
   prompts/chat-session-summary
   prompts/resume-chat-session
   prompts/plan-and-execute
   ;; Example parameterized prompt - code review - see function below
   #_(code-review-prompt-example)])

(defn my-tools [nrepl-client-atom]
  [;; read-only tools
   (directory-tree-tool/directory-tree-tool nrepl-client-atom)
   (unified-read-file-tool/unified-read-file-tool nrepl-client-atom)
   (new-grep-tool/grep-tool nrepl-client-atom)
   (glob-files-tool/glob-files-tool nrepl-client-atom)
   (think-tool/think-tool nrepl-client-atom)
   ;; experimental todo list / scratch pad
   (scratch-pad-tool/scratch-pad-tool nrepl-client-atom)

   ;; eval
   (eval-tool/eval-code nrepl-client-atom)
   ;; now runs in the nrepl process
   (bash-tool/bash-tool nrepl-client-atom)

   ;; editing tools
   (combined-edit-tool/unified-form-edit-tool nrepl-client-atom)
   (new-form-edit-tool/sexp-replace-tool nrepl-client-atom)
   (file-edit-tool/file-edit-tool nrepl-client-atom)
   (file-write-tool/file-write-tool nrepl-client-atom)

   ;; introspection
   (project-tool/inspect-project-tool nrepl-client-atom)

   ;; Agents these are read only
   ;; these require api keys to be configured
   (dispatch-agent-tool/dispatch-agent-tool nrepl-client-atom)
   ;; not sure how useful this is
   (architect-tool/architect-tool nrepl-client-atom)

   ;; experimental 
   (code-critique-tool/code-critique-tool nrepl-client-atom)])

;; not sure if this is even needed
(def nrepl-client-atom (atom nil))

;; start the server
(defn start-mcp-server [nrepl-args]
  ;; the nrepl-args are a map with :port :host :tls-keys-file]
  (let [nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        resources (my-resources nrepl-client-map working-dir)
        _ (reset! nrepl-client-atom nrepl-client-map)
        tools (my-tools nrepl-client-atom)
        prompts (my-prompts working-dir)
        mcp (core/mcp-server)]
    (doseq [tool tools]
      (core/add-tool mcp tool))
    (doseq [resource resources]
      (core/add-resource mcp resource))
    (doseq [prompt prompts]
      (core/add-prompt mcp prompt))
    (swap! nrepl-client-atom assoc :mcp-server mcp)
    nil))

;; Example parameterized prompt
(defn code-review-prompt-example []
  {:name "code-review-prompt"
   :description "Generate a code review prompt for a specific file or namespace"
   :arguments [{:name "file-path"
                :description "The file path to review"
                :required? true}
               {:name "focus-areas"
                :description "Specific areas to focus on (e.g., 'performance,style,testing')"
                :required? false}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [file-path (get request-args "file-path")
                      focus-areas (get request-args "focus-areas" "general code quality")]
                  (clj-result-k
                   {:description (str "Code review for: " file-path)
                    :messages
                    [{:role :user
                      :content
                      (str "Please perform a thorough code review of the file at: "
                           file-path "\n\n"
                           "Focus areas: " focus-areas "\n\n"
                           "Consider:\n"
                           "1. Code style and Clojure idioms\n"
                           "2. Performance implications\n"
                           "3. Error handling\n"
                           "4. Function complexity and readability\n"
                           "5. Missing tests or edge cases\n\n"
                           "Please use the read_file tool to examine the code, "
                           "then provide detailed feedback.")}]})))})

;; -Djdk.attach.allowAttachSelf is needed on the nrepl server if you want the mcp-server eval tool
;; to be able to interrupt long running evals

;; TODO make a main fn that uses clojure.tools.cli and takes port host and tls-keys-file args
