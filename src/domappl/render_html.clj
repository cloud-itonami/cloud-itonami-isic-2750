(ns domappl.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo previously shipped NO demo page and no generator at all.
  This namespace drives the REAL actor stack -- `domappl.operation`
  (the langgraph-clj StateGraph) -> `domappl.governor` (the
  independent censor) -> `domappl.store` (the append-only SSoT) --
  and renders the resulting store + run audit. Nothing on the page is
  hand-typed: every batch, equipment unit, maintenance window,
  shipment draft, safety concern, HARD-hold rule and audit fact below
  is read back out of the objects the run actually produced.

  Grounding discipline: the only entity identifiers, product types,
  models, test voltages, quantities, defect rates and dates used
  anywhere in the scenario come from this repo's own
  `domappl.store/sample-batches` / `sample-equipment` seed and this
  repo's own committed demo driver `domappl.sim`. No customer, no
  plant, no place, no carrier and no tracking number is invented --
  the seed does not name any, so neither does this page.

  Determinism: no wall-clock timestamp reaches the page. The registry
  sequences (`MNT-000000`, `SHP-000000`) come from the store's own
  monotonic counters, which start at 0 in a fresh `seed-db`, so two
  consecutive runs are byte-identical. Verify by rendering twice into
  two scratch files and diffing.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [domappl.store :as store]
            [domappl.operation :as op]
            [domappl.governor :as governor]
            [domappl.phase :as phase]
            [domappl.registry :as registry]
            [langgraph.graph :as g]))

(def ^:private coordinator
  "The operator context every request below runs under -- the same
  `:actor-id`/`:actor-role`/`:phase` triple this repo's own
  `domappl.sim` uses, so the page shows the actor at its default
  rollout phase (3, `supervised-auto`) rather than a phase invented to
  make the demo look better."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private required-hard-rules
  "Every HARD invariant `domappl.governor` publishes. `-main` asserts
  that the REAL governor output of the scenario below contains a hold
  for each one -- a build-time invariant, not a comment. If a rule is
  weakened, deleted, or made unreachable, this generator refuses to
  write the console rather than quietly shipping a page that claims
  coverage it no longer has."
  #{:not-propose-effect
    :unknown-op
    :equipment-control-blocked
    :equipment-actuate-blocked
    :certification-authority-blocked
    :equipment-not-verified
    :already-scheduled
    :batch-not-verified
    :shipment-quantity-exceeded
    :invalid-product-type
    :invalid-dielectric-test-kv
    :invalid-defect-rate})

;; ----------------------------- driving the real actor -----------------------------

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- resume! [actor tid approval]
  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))

(defn- step!
  "Runs ONE request through the real graph, optionally resuming the
  human-approval interrupt, and records what actually came back.
  `:decision` is `:approve`, `:reject`, or absent (the request never
  reaches a human -- either it auto-commits or the governor HARD-holds
  it)."
  [actor log tid label request & [{:keys [decision]}]]
  (let [r1 (exec! actor tid request)
        r2 (case decision
             :approve (resume! actor tid {:status :approved :by (:actor-id coordinator)})
             :reject  (resume! actor tid {:status :rejected :by (:actor-id coordinator)})
             nil)
        st (:state (or r2 r1))]
    (swap! log conj {:thread tid
                     :label label
                     :op (:op request)
                     :subject (:subject request)
                     :reached-human? (some? r2)
                     :decision decision
                     :disposition (:disposition st)
                     :audit (vec (:audit st))})
    st))

(defn run-demo!
  "Runs a fresh seeded store (`domappl.store/seed-db`) through a
  scenario covering every disposition this actor can reach.

  CLEAN LIFECYCLE (all against seeded, verified+registered entities):
    - `batch-001` production-batch logging -- the ONE op in any
      phase's `:auto` set, so it auto-commits at phase 3 with no human.
    - `mnt-1` maintenance window on `assy-001` (verified+registered
      assembly line) -- the governor clears it, but `domappl.phase`
      never puts `:schedule-maintenance` in any phase's `:auto` set,
      so it escalates; a human approves.
    - `concern-1` safety concern on `assy-001` -- ALWAYS escalates
      (`:coordination/safety-concern` is in `governor/high-stakes`),
      regardless of the 0.9 confidence; a human approves.
    - `ship-1` shipment of 50 units against `batch-001` (500 logged,
      100 already shipped) -- escalates; a human approves.
    - `ship-4` shipment of 20 units against `batch-002` (200 logged,
      180 already shipped) -- fills the batch EXACTLY to its recorded
      production quantity. The registry documents this boundary as
      legal (it is compared at 1/10000 of a unit precisely so an exact
      fill is not rounded into an overflow); a human approves.
    - `mnt-4` maintenance window on `assy-001` -- escalates and the
      human REJECTS it. The rejection is a hold too, but an
      `:approval-rejected` one, not a governor HARD hold: the
      distinction is what tells a reader whether a human ever saw the
      request.

  HARD HOLDS (each exercised directly and independently, one request
  per failure mode -- never only as a side effect of a happy path):
  every rule in `required-hard-rules`, plus a second, structurally
  DIFFERENT path into `:shipment-quantity-exceeded` (a shipment that
  states no quantity at all, which the registry's
  `shipment-quantity-exceeded-checkable?` refuses as un-computable
  rather than letting it fall through as headroom), and a second op
  reaching `:certification-authority-blocked` (it is checked on ANY
  op, not only batch logging).

  Returns `{:db <store> :runs [..]}` -- `:runs` carries each thread's
  own final `:audit` channel, which is where the approval identity
  lives (see `approver-retention` for why that matters)."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        log (atom [])
        step (fn [& args] (apply step! actor log args))]

    ;; --- clean lifecycle -------------------------------------------------
    (step "t1" "production-batch logging (phase-3 auto-commit, no human)"
          {:op :log-production-batch :effect :propose :subject "batch-001"
           :patch {:product-type :refrigerator :last-assessed "2026-07-14"}})

    (step "t2" "maintenance window on a verified assembly line"
          {:op :schedule-maintenance :effect :propose :subject "mnt-1"
           :value {:equipment-id "assy-001" :maintenance-type :conveyor-inspection
                   :scheduled-date "2026-08-01" :actuate-equipment? false}}
          {:decision :approve})

    (step "t3" "safety concern (always escalates, any confidence)"
          {:op :flag-safety-concern :effect :propose :subject "concern-1"
           :value {:equipment-id "assy-001" :severity :moderate
                   :description "冷媒配管の圧力異常兆候、コンプレッサー系統の漏れ懸念"}}
          {:decision :approve})

    (step "t4" "shipment within the batch's own logged quantity"
          {:op :coordinate-shipment :effect :propose :subject "ship-1"
           :value {:batch-id "batch-001" :units 50.0}}
          {:decision :approve})

    (step "t5" "shipment filling a batch EXACTLY to its recorded quantity"
          {:op :coordinate-shipment :effect :propose :subject "ship-4"
           :value {:batch-id "batch-002" :units 20.0}}
          {:decision :approve})

    (step "t6" "maintenance window the human REJECTS"
          {:op :schedule-maintenance :effect :propose :subject "mnt-4"
           :value {:equipment-id "assy-001" :maintenance-type :conveyor-inspection
                   :scheduled-date "2026-09-01" :actuate-equipment? false}}
          {:decision :reject})

    ;; --- HARD holds ------------------------------------------------------
    (step "h1" "caller's own request :effect is not :propose"
          {:op :log-production-batch :effect :direct-write :subject "batch-001"
           :patch {:product-type :refrigerator}})

    (step "h2" "op outside the closed four-op allowlist"
          {:op :actuate-assembly-line :effect :propose :subject "batch-001"})

    (step "h3" "maintenance against an UNVERIFIED/unregistered test bench"
          {:op :schedule-maintenance :effect :propose :subject "mnt-2"
           :value {:equipment-id "final-test-002" :maintenance-type :calibration
                   :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (step "h4" "shipment against an UNVERIFIED/unregistered batch"
          {:op :coordinate-shipment :effect :propose :subject "ship-2"
           :value {:batch-id "batch-003" :units 100.0}})

    (step "h5" "shipment past the batch's own logged production quantity"
          {:op :coordinate-shipment :effect :propose :subject "ship-3"
           :value {:batch-id "batch-002" :units 100.0}})

    (step "h6" "shipment stating no quantity -- headroom not computable"
          {:op :coordinate-shipment :effect :propose :subject "ship-5"
           :value {:batch-id "batch-001"}})

    (step "h7" "maintenance proposal that tries to ACTUATE the equipment"
          {:op :schedule-maintenance :effect :propose :subject "mnt-3"
           :value {:equipment-id "assy-001" :maintenance-type :force-run
                   :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (step "h8" "double-scheduling the same maintenance window"
          {:op :schedule-maintenance :effect :propose :subject "mnt-1"
           :value {:equipment-id "assy-001" :maintenance-type :conveyor-inspection
                   :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (step "h9" "batch patch declaring a fabricated product type"
          {:op :log-production-batch :effect :propose :subject "batch-001"
           :patch {:product-type :unobtainium}})

    (step "h10" "batch patch with an implausible dielectric-test voltage"
          {:op :log-production-batch :effect :propose :subject "batch-001"
           :patch {:dielectric-test-kv 999999.0}})

    (step "h11" "batch patch with an implausible defect rate"
          {:op :log-production-batch :effect :propose :subject "batch-001"
           :patch {:defect-rate-percent 999.0}})

    (step "h12" "batch logging that tries to self-issue a safety certification"
          {:op :log-production-batch :effect :propose :subject "batch-001"
           :patch {:issue-certification? true}})

    (step "h13" "shipment that tries to self-issue a safety certification"
          {:op :coordinate-shipment :effect :propose :subject "ship-6"
           :value {:batch-id "batch-001" :units 10.0 :issue-certification? true}})

    {:db db :runs @log}))

;; ----------------------------- derived observations -----------------------------

(defn- committed-subjects
  "Subjects the run actually committed for `op`, read back out of the
  store's own ledger -- the only list of shipment/maintenance ids this
  page will iterate. Nothing is added by hand."
  [ledger op]
  (->> ledger
       (filter #(and (= :committed (:t %)) (= op (:op %))))
       (mapv :subject)))

(defn- all-shipments
  "`domappl.store/Store` exposes `shipment` by id but no
  `all-shipments`, so the ids come from the ledger's own committed
  `:coordinate-shipment` facts."
  [db ledger]
  (keep #(store/shipment db %) (committed-subjects ledger :coordinate-shipment)))

(defn- approver-retention
  "MEASURED at render time, never asserted.

  `domappl.operation`'s approval node attaches the approver to the
  commit record's `:payload`. Whether that identity is still
  retrievable afterwards is a property of `domappl.store`'s
  `commit-record!`, which this function inspects rather than assumes:
  it counts the approvals the run actually granted, then counts how
  many SSoT records and how many ledger facts came back carrying an
  approver id. If someone later changes the store, these counts change
  with it and the disclosure rendered from them changes too -- a
  hardcoded note would become a lie the moment the store was fixed."
  [db runs]
  (let [run-audit (mapcat :audit runs)
        granted (filter #(= :approval-granted (:t %)) run-audit)
        registers (concat (store/all-batches db)
                          (store/all-equipment db)
                          (store/all-maintenance db)
                          (store/safety-concerns db)
                          (all-shipments db (store/ledger db)))
        approver-keys #{:approved-by :approver :approved_by}
        retained (filter (fn [r] (some #(some? (get r %)) approver-keys)) registers)
        ledger-with-approver (filter (fn [f] (some #(some? (get f %))
                                                   (conj approver-keys :by)))
                                     (store/ledger db))]
    {:approvals-granted (count granted)
     :approver-ids (vec (distinct (keep :by granted)))
     :registers-inspected (count registers)
     :registers-retaining-approver (count retained)
     :ledger-facts-carrying-approver (count ledger-with-approver)
     :approval-granted-facts-in-ledger
     (count (filter #(= :approval-granted (:t %)) (store/ledger db)))}))

(defn- hard-hold-rules
  "Every distinct HARD rule the REAL governor fired in this run,
  with the governor's own detail string for the first hold that fired
  it. Sorted by rule name so the page is byte-stable."
  [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)]
    (->> holds
         (mapcat (fn [f] (map #(assoc % :subject (:subject f) :op (:op f))
                              (:violations f))))
         (reduce (fn [m v] (if (contains? m (:rule v)) m (assoc m (:rule v) v))) {})
         (sort-by (comp name key))
         (mapv val))))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- flag [true? yes no]
  (if true?
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"warn\">" no "</span>")))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      nil "<span class=\"muted\">no activity this run</span>"
      :committed "<span class=\"ok\">committed</span>"
      :approval-rejected "<span class=\"warn\">held &middot; approver rejected</span>"
      :governor-hold (str "<span class=\"critical\">HARD hold &middot; "
                          (esc (str/join ", " (map kw (:basis f)))) "</span>")
      "<span class=\"muted\">in progress</span>")))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (if lead (str "    <p class=\"muted\">" lead "</p>\n") "")
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- batch-rows [db ledger]
  (for [b (store/all-batches db)]
    (row (str "<code>" (esc (:id b)) "</code>")
         (esc (kw (:product-type b)))
         (esc (:model b))
         (esc (:dielectric-test-kv b))
         (esc (:quantity-units b))
         (esc (:shipped-units b))
         (esc (:defect-rate-percent b))
         (flag (registry/batch-ready? b) "verified &amp; registered" "NOT verified/registered")
         (status-cell ledger (:id b)))))

(defn- equipment-rows [db]
  (for [e (store/all-equipment db)]
    (row (str "<code>" (esc (:id e)) "</code>")
         (esc (kw (:kind e)))
         (flag (registry/equipment-ready? e) "verified &amp; registered" "NOT verified/registered")
         (esc (or (:last-maintenance-date e) "—"))
         (esc (or (:last-scheduled-maintenance-date e) "—")))))

(defn- maintenance-rows [db ledger]
  (for [m (store/all-maintenance db)]
    (row (str "<code>" (esc (:id m)) "</code>")
         (str "<code>" (esc (:equipment-id m)) "</code>")
         (esc (kw (:maintenance-type m)))
         (esc (:scheduled-date m))
         (str "<code>" (esc (:maintenance-number m)) "</code>")
         (flag (true? (:scheduled? m)) "scheduled" "draft")
         (status-cell ledger (:id m)))))

(defn- shipment-rows [db ledger]
  (for [s (all-shipments db ledger)]
    (row (str "<code>" (esc (:id s)) "</code>")
         (str "<code>" (esc (:batch-id s)) "</code>")
         (esc (:units s))
         (str "<code>" (esc (:shipment-number s)) "</code>")
         (status-cell ledger (:id s)))))

(defn- concern-rows [db ledger]
  (for [c (store/safety-concerns db)]
    (row (str "<code>" (esc (:id c)) "</code>")
         (str "<code>" (esc (:equipment-id c)) "</code>")
         (str "<span class=\"warn\">" (esc (kw (:severity c))) "</span>")
         (esc (:description c))
         (status-cell ledger (:id c)))))

(defn- draft-record-rows [db]
  (concat
   (for [r (store/maintenance-history db)]
     (row (str "<code>" (esc (get r "record_id")) "</code>")
          (esc (get r "kind"))
          (str "<code>" (esc (or (get r "maintenance_id") (get r "shipment_id"))) "</code>")
          (esc (or (get r "equipment_id") "—"))
          (flag (true? (get r "immutable")) "immutable" "mutable")))
   (for [r (store/shipment-history db)]
     (row (str "<code>" (esc (get r "record_id")) "</code>")
          (esc (get r "kind"))
          (str "<code>" (esc (or (get r "maintenance_id") (get r "shipment_id"))) "</code>")
          (esc (or (get r "equipment_id") "—"))
          (flag (true? (get r "immutable")) "immutable" "mutable")))))

(defn- scenario-rows [runs]
  (for [{:keys [thread label op subject reached-human? decision disposition]} runs]
    (row (str "<code>" (esc thread) "</code>")
         (esc label)
         (str "<code>:" (esc (kw op)) "</code>")
         (str "<code>" (esc subject) "</code>")
         (if reached-human?
           (str "<span class=\"warn\">yes &middot; " (esc (kw decision)) "</span>")
           "<span class=\"muted\">never reached a human</span>")
         (case disposition
           :commit "<span class=\"ok\">commit</span>"
           :hold (if reached-human?
                   "<span class=\"warn\">hold (approver rejected)</span>"
                   "<span class=\"critical\">hold</span>")
           (str "<span class=\"muted\">" (esc (kw disposition)) "</span>")))))

(defn- hard-rule-rows [ledger]
  (for [{:keys [rule op subject detail]} (hard-hold-rules ledger)]
    (row (str "<code>:" (esc (kw rule)) "</code>")
         (str "<code>:" (esc (kw op)) "</code>")
         (str "<code>" (esc subject) "</code>")
         (esc detail))))

(defn- gate-rows
  "The op-by-op posture, derived from `domappl.phase`'s own tables and
  `domappl.governor`'s own allowlists rather than described by hand --
  if a future edit adds an op to a phase's `:auto` set, this table
  says so."
  []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (for [o (sort-by name (concat phase/read-ops phase/write-ops))]
      (row (str "<code>:" (esc (kw o)) "</code>")
           (if (contains? governor/allowed-ops o)
             "<span class=\"ok\">in the closed allowlist</span>"
             "<span class=\"critical\">not routable</span>")
           (cond
             (not (contains? writes o))
             (str "<span class=\"critical\">blocked at phase " ph " (" (esc label) ")</span>")
             (contains? auto o)
             (str "<span class=\"ok\">may auto-commit when governor-clean</span>")
             :else
             "<span class=\"warn\">ALWAYS human approval &middot; never in any phase's :auto set</span>")
           (if (contains? governor/high-stakes :coordination/safety-concern)
             (if (= o :flag-safety-concern)
               "<span class=\"warn\">high-stakes &middot; escalates at any confidence</span>"
               (str "<span class=\"muted\">confidence floor "
                    (esc governor/confidence-floor) "</span>"))
             "<span class=\"muted\">—</span>")))))

(defn- ledger-rows [ledger]
  (for [f ledger]
    (row (esc (kw (:t f)))
         (str "<code>:" (esc (kw (:op f))) "</code>")
         (str "<code>" (esc (:subject f)) "</code>")
         (esc (:actor f))
         (esc (or (some->> (:basis f) (map kw) (str/join ", ")) ""))
         (esc (or (:summary f)
                  (some->> (:violations f) (map :detail) (str/join " / "))
                  "")))))

(defn- approver-section
  "Renders the approver-attribution disclosure FROM the measured
  observation, so the wording follows the code rather than the other
  way round."
  [obs]
  (let [{:keys [approvals-granted approver-ids registers-inspected
                registers-retaining-approver ledger-facts-carrying-approver
                approval-granted-facts-in-ledger]} obs
        retained? (pos? registers-retaining-approver)
        in-ledger? (pos? ledger-facts-carrying-approver)
        verdict
        (cond
          (zero? approvals-granted)
          "この実行では承認が発生していないため、承認者の保持性は測定できていない。"

          retained?
          (str "承認者 ID は SSoT レコードに保持されている ("
               registers-retaining-approver " / " registers-inspected
               " 件)。下表の承認者はレコードから直接読める。")

          in-ledger?
          (str "承認者 ID は SSoT レコードには保持されていないが、"
               "台帳のファクトからは読める。")

          :else
          (str "承認者 ID は SSoT からは復元できない。"
               "<code>domappl.operation</code> は承認者を commit レコードの "
               "<code>:payload</code> に載せるが、<code>domappl.store/commit-record!</code> は "
               "<code>:value</code> しか読まないため、4 つの effect 分岐のどれもこの値を書かない。"
               "さらに <code>:approval-granted</code> ファクトは実行中の <code>:audit</code> "
               "チャネルにしか現れず、台帳には追記されない。"
               "したがって下の承認者は <strong>監査ログ由来であり、レコードには残っていない</strong>。"
               "「誰も承認していない」と「承認者が落ちた」を読者が区別できるように、"
               "省略せず明示する。"))]
    (str "  <section class=\"card\">\n"
         "    <h2>Approver attribution — measured, not asserted</h2>\n"
         "    <p class=\"muted\">この節の文言は <code>domappl.render-html/approver-retention</code> が"
         "レンダリング時に実際のストアを検査した結果から生成される。ストアの実装が変われば文言も変わる。</p>\n"
         "    <table>\n"
         "      <thead><tr><th>Observation</th><th>Value</th></tr></thead>\n"
         "      <tbody>\n"
         (str/join
          "\n"
          [(row "この実行で成立した承認 (<code>:approval-granted</code>)" approvals-granted)
           (row "承認者 ID (実行中の audit チャネルより)"
                (if (seq approver-ids)
                  (str/join ", " (map #(str "<code>" (esc %) "</code>") approver-ids))
                  "—"))
           (row "検査した SSoT レコード数" registers-inspected)
           (row "承認者 ID を保持していた SSoT レコード数"
                (str (if retained? "<span class=\"ok\">" "<span class=\"critical\">")
                     registers-retaining-approver "</span>"))
           (row "承認者 ID を保持していた台帳ファクト数"
                (str (if in-ledger? "<span class=\"ok\">" "<span class=\"critical\">")
                     ledger-facts-carrying-approver "</span>"))
           (row "台帳に追記された <code>:approval-granted</code> ファクト数"
                (str (if (pos? approval-granted-facts-in-ledger)
                       "<span class=\"ok\">" "<span class=\"critical\">")
                     approval-granted-facts-in-ledger "</span>"))])
         "\n"
         "      </tbody>\n"
         "    </table>\n"
         "    <p>" verdict "</p>\n"
         "  </section>\n")))

(defn render
  "Renders the whole console from a store `db` and the run log
  `runs` that `run-demo!` produced."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        obs (approver-retention db runs)
        approver-note (if (pos? (:registers-retaining-approver obs))
                        ""
                        " <em>(audit only — not retained in record)</em>")]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2750 &middot; domestic-appliance plant operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of domestic appliances (ISIC 2750) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; "
     "maintenance scheduling / safety flags / shipment coordination always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <p>このページは手書きではない。<code>clojure -M:dev:render-html</code> が "
     "<code>domappl.operation</code>(langgraph-clj StateGraph) → <code>domappl.governor</code> → "
     "<code>domappl.store</code> の実パイプラインを走らせ、その結果のストアと監査チャネルを"
     "そのまま描画したもの。エンティティは全て <code>domappl.store</code> の seed 由来で、"
     "捏造した顧客名・地名・運送番号は 1 つも無い。時刻はページに載らないため、"
     "同じ seed に対する再実行はバイト単位で一致する。</p>\n"
     "    <p class=\"muted\">Phase " (esc phase/default-phase) " ("
     (esc (:label (get phase/phases phase/default-phase)))
     ") &middot; actor <code>" (esc (:actor-id coordinator)) "</code> / role <code>:"
     (esc (kw (:actor-role coordinator))) "</code></p>\n"
     "  </section>\n"

     (section "Scenario — every request this run actually made"
              (str "各行は 1 スレッド = 1 グラフ実行。"
                   "「never reached a human」の行は HARD hold で、人間の目に触れる前に止まっている。")
              ["Thread" "What it exercises" "Op" "Subject" "Reached a human?" "Disposition"]
              (scenario-rows runs))

     (section "Production batches"
              (str "<code>domappl.store</code> の SSoT。"
                   "<code>:verified?</code> と <code>:registered?</code> は"
                   "ガバナーが提案の自己申告ではなくこのレコードから独立に読み直す ground truth。")
              ["Batch" "Product type" "Model" "Dielectric test (kV)"
               "Logged quantity" "Shipped" "Defect rate (%)" "Ground truth" "Last op"]
              (batch-rows db ledger))

     (section "Assembly / test-bench equipment"
              (str "未検証・未登録の設備に対する保守作業予定は HARD hold になる —— "
                   "<code>final-test-002</code> がその実例。")
              ["Equipment" "Kind" "Ground truth" "Last maintenance" "Last scheduled"]
              (equipment-rows db))

     (section "Maintenance windows (committed drafts)"
              (str "この actor が作るのは DRAFT のみ。設備を直接操作(actuate)する提案は"
                   "恒久的に禁止されており、フェーズでも人間の承認でも上書きできない。")
              ["Maintenance" "Equipment" "Type" "Scheduled date" "Record no." "State" "Last op"]
              (maintenance-rows db ledger))

     (section "Shipment coordination (committed drafts)"
              (str "数量はガバナーがバッチ自身の記録済み生産数量と累積出荷実績から"
                   "独立に再計算する。提案側の自己申告は信用されない。")
              ["Shipment" "Batch" "Units" "Record no." "Last op"]
              (shipment-rows db ledger))

     (section "Safety concerns"
              (str "安全懸念は confidence によらず常にエスカレーションする"
                   "(<code>governor/high-stakes</code>)。"
                   "また、対象設備が未検証であることを理由に報告を止めることはしない。")
              ["Concern" "Equipment" "Severity" "Description" "Last op"]
              (concern-rows db ledger))

     (section "Registry draft records"
              (str "<code>domappl.registry</code> が構築した不変ドラフト。"
                   "付随する証明書は全て <code>status: draft-unsigned</code> であり、"
                   "この actor は家電製品安全認証(UL/CE/CSA 等)を自己発行しない。")
              ["Record no." "Kind" "Subject" "Equipment" "Immutability"]
              (draft-record-rows db))

     (section "Action gate (Domestic Appliance Plant Operations Governor × rollout phase)"
              (str "この表は <code>domappl.phase/phases</code> と "
                   "<code>domappl.governor</code> の allowlist から導出している —— "
                   "手書きの説明ではないので、将来 <code>:auto</code> 集合が変われば表も変わる。")
              ["Op" "Routable?" "Phase gate" "Escalation"]
              (gate-rows))

     (section "HARD invariants the governor actually fired this run"
              (str "詳細文はガバナー自身が生成したもの。"
                   "HARD hold は人間の承認では上書きできない —— "
                   "そもそも人間まで届かない。")
              ["Rule" "Op" "Subject" "Governor's own detail"]
              (hard-rule-rows ledger))

     (approver-section obs)

     (section "Audit ledger (this run)"
              (str "append-only の決定ファクト列。"
                   "この actor のライフサイクルは「どのバッチが記録され、"
                   "どの検証済み設備に保守が組まれ、どの数量で出荷が調整され、"
                   "どの安全懸念が上がったか」を常にこの不変ログへの query として答える。")
              ["Fact" "Op" "Subject" "Actor" "Basis" "Detail"]
              (ledger-rows ledger))

     "  <footer>\n"
     "    <p class=\"muted\">Generated by <code>domappl.render-html</code> from a live actor run"
     approver-note ". "
     "Scenario threads: " (esc (count runs)) " &middot; ledger facts: " (esc (count ledger))
     " &middot; distinct HARD rules fired: " (esc (count (hard-hold-rules ledger)))
     ".</p>\n"
     "  </footer>\n"
     "</main>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn- assert-hard-holds!
  "BUILD-TIME INVARIANT, not a comment.

  Refuses to write the console unless the REAL governor output of this
  run contains at least one `:governor-hold` fact AND a hold for every
  rule in `required-hard-rules`. A demo page whose governor never said
  no is theatre; a page that silently stops exercising a rule is worse,
  because it keeps claiming the coverage. Both fail the build here."
  [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        fired (set (map :rule (hard-hold-rules ledger)))
        missing (sort-by name (remove fired required-hard-rules))]
    (when (empty? holds)
      (throw (ex-info "REFUSING to write the console: the real governor produced ZERO :governor-hold facts. A console whose governor never held anything is not evidence that the governor works."
                      {:ledger-facts (count ledger)})))
    (when (seq missing)
      (throw (ex-info (str "REFUSING to write the console: the scenario no longer exercises "
                           (count missing) " HARD invariant(s): "
                           (str/join ", " (map str missing)))
                      {:missing (vec missing) :fired (vec (sort-by name fired))})))
    {:holds (count holds) :rules (count fired)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        {:keys [holds rules]} (assert-hard-holds! ledger)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count runs) " scenario threads, "
                  (count ledger) " ledger facts, "
                  holds " governor HARD holds across "
                  rules " distinct rules, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
