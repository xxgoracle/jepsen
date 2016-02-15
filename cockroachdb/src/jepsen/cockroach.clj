
(ns jepsen.cockroach
    "Tests for CockroachDB"
    (:require [clojure.tools.logging :refer :all]
            [clj-ssh.ssh :as ssh]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [multiset.core :as multiset]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
             [core :as jepsen]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [model :as model]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :as util]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.ubuntu :as ubuntu]))

(defn eval!
  "Evals a sql string from the command line."
  [s]
   (info "Executing SQL: " s)
  (c/exec "/home/ubuntu/sql.sh" (str (System/currentTimeMillis)) s))

(defn setup-db!
  "Set up the jepsen database in the cluster."
  [node]
  (info "Setting up db from node " (name node))
  (eval! "drop database if exists jepsen;")
  (eval! "create database jepsen;")
  (eval! "create table jepsen.test (name string, val int, primary key (name));")
  (eval! "insert into jepsen.test values ('a', 0);")
  (eval! "create table jepsen.set (val int);")
  (dorun (for [x (range 5)] (do
                              (eval! (str "create table jepsen.mono" x " (val int, sts bigint, node int, tb int);"))
                              (eval! (str "insert into jepsen.mono" x " values (-1, extract(epoch_nanoseconds from now()), -1, -1);")))))
  (eval! "create table if not exists jepsen.accounts (id int not null primary key, balance bigint not null);")
  )

(defn stop!
  "Remove the jepsen database from the cluster."
  [node]
  (eval! "drop database if exists jepsen;"))

(def log-files
  ["/home/ubuntu/logs/cockroach.stderr"
   "/home/ubuntu/logs/sql.log"])

(defn str->int [str]
  (let [n (read-string str)]
    (if (number? n) n nil)))

(defn db
  "Sets up and tears down CockroachDB."
  []
  (reify db/DB
    (setup! [_ test node]

      (info node "Stopping cockroachdb...")
      (c/exec "/home/ubuntu/stop.sh")
      (jepsen/synchronize test)
      (info node "Resetting the clocks and starting cockroachdb...")
      (c/exec "/home/ubuntu/restart.sh")
      (jepsen/synchronize test)
      
      (if (= node (jepsen/primary test)) (setup-db! node))

      (info node "Setup complete")
      (Thread/sleep 1000))

    (teardown! [_ test node]

      (if (= node (jepsen/primary test)) (stop! node))
      (jepsen/synchronize test)
      (apply c/exec :truncate :-c :--size 0 log-files)
      )
    
    db/LogFiles
    (log-files [_ test node] log-files)))

; -------------------- Accessing CockroachDB via SQL-over-SSH -----------------

(defn ssh-open
  "Open a SSH session to the given node."
  [node]
  (let [agent (ssh/ssh-agent {})
        host (name node)
        conn (ssh/session agent host {:username "ubuntu" :strict-host-key-checking :no})]
    conn))

(defn ssh-sql-sh
  "Evals a sql string from the command line over an existing SSH connection."
  [conn s]
  (ssh/ssh conn {:cmd (str "/home/ubuntu/sql.sh " (System/currentTimeMillis) " " s)}))

(defn ssh-sql
    "Evals a SQL string and return the error status and messages if any"
  [conn stmts]
  (let [res (ssh-sql-sh conn stmts)
        out (str/split-lines (str/trim-newline (:out res)))
        err (let [rerr (str "sql error: " (str/join " " out))]
              (cond (not (nil? (re-find #"retry txn" rerr))) :retry
                    (not (nil? (re-find #"read.*encountered previous write" rerr))) :abort-uncertain
                    :else rerr))]
    [res out err]))
  

;-------------------- Common definitions ------------------------------------

(defn clock-milli-scrambler
  "Randomizes the system clock of all nodes within a dt-millisecond window."
  [dt]
  (reify client/Client
    (setup! [this test _]
      this)

    (invoke! [this test op]
      (assoc op :value
                (c/on-many (:nodes test)
                           (c/exec "/home/ubuntu/adjtime" (str (- (rand-int (* 2 dt)) dt))))))

    (teardown! [this test]
      (c/on-many (:nodes test)
                 (c/su (c/exec "ntpdate" "ntp.ubuntu.com"))))
    ))

(defn basic-test
  [nodes opts]
  (merge tests/noop-test
         {:nodes   nodes
          :name    (str "cockroachdb " (:name opts))
          :os      ubuntu/os
          :db      (db)
          :nemesis (nemesis/partition-random-halves)
          }
         (dissoc opts :name)))

                                        ;
;-------------------- Test for an atomic counter ----------------------------

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn ssh-cnt-client
  "A client for a single compare-and-set register, using sql cli over ssh"
  [conn]
  (reify client/Client
    (setup! [_ test node]
      (ssh-cnt-client (ssh-open node)))
    
    (invoke! [this test op]
      (util/timeout 5000 (assoc op :type :info, :error :timeout)
               (case (:f op)
                 :read (let [[res out err]
                             (ssh-sql conn (str "begin "
                                                "\"set transaction isolation level serializable\" "
                                                "\"select val from jepsen.test\"  "
                                                "commit"))]
                         (if (zero? (:exit res))
                           (assoc op :type :ok, :value (str->int (nth out 4)))
                           (assoc op :type :info, :error err)))
                 :write (let [[res out err] (ssh-sql conn (str "begin "
                                                               "\"set transaction isolation level serializable\" "
                                                               "\"update jepsen.test set val=" (:value op) " where name='a'\" "
                                                               "commit"))]
                          (if (and (zero? (:exit res)) (= (last out) "OK"))
                            (assoc op :type :ok)
                            (assoc op :type :info, :error err)))
                 :cas (let [[value' value] (:value op)
                            [res out err] (ssh-sql conn (str "begin "
                                                             "\"set transaction isolation level serializable\" "
                                                             "\"select val from jepsen.test\" "
                                                             "\"update jepsen.test set val=" value " where name='a' and val=" value' "\" "
                                                             "\"select val from jepsen.test\" "
                                                             " commit"))]
                        (if (zero? (:exit res))
                          (assoc op 
                                 :type (if (and (= (last out) "OK")
                                                (= (str->int (nth out 4)) value')
                                                (= (str->int (nth out 8)) value))
                                         :ok :fail),
                                 :error err)
                          (assoc op :type :info, :error err)))
                 ))
      )
      

    (teardown! [_ test]
    	   (ssh/disconnect conn))
    ))

(defn atomic-test
  [nodes]
  (basic-test nodes
   {
    :name    "atomic"
    :client  (ssh-cnt-client nil)
    :generator (->> (gen/mix [r w cas])
                    (gen/stagger 1)
                    (gen/nemesis
                     (gen/seq (cycle [(gen/sleep 5)
                                      {:type :info, :f :start}
                                      (gen/sleep 5)
                                      {:type :info, :f :stop}])))
                    (gen/time-limit 30))
    :model   (model/cas-register 0)
    :checker (checker/compose
              {:perf   (checker/perf)
               :linear checker/linearizable})
    }
   ))

;-------------------- Test for a distributed set ----------------------------

(defn check-set-unique
  "Given a set of :add operations followed by a final :read, verifies that
  every successfully added element is present in the read, and that the read
  contains only elements for which an add was attempted, and that all
  elements are unique."
  []
  (reify checker/Checker
    (check [this test model history]
      (let [attempts (->> history
                          (r/filter op/invoke?)
                          (r/filter #(= :add (:f %)))
                          (r/map :value)
                          (into #{}))
            adds (->> history
                      (r/filter op/ok?)
                      (r/filter #(= :add (:f %)))
                      (r/map :value)
                      (into #{}))
            final-read-l (->> history
                          (r/filter op/ok?)
                          (r/filter #(= :read (:f %)))
                          (r/map :value)
                          (reduce (fn [_ x] x) nil))]
        (if-not final-read-l
          {:valid? false
           :error  "Set was never read"})

        (let [final-read  (into #{} final-read-l)
                                        
              dups        (remove final-read final-read-l)
              
              ;The OK set is every read value which we tried to add
              ok          (set/intersection final-read attempts)

              ; Unexpected records are those we *never* attempted.
              unexpected  (set/difference final-read attempts)

              ; Lost records are those we definitely added but weren't read
              lost        (set/difference adds final-read)

              ; Recovered records are those where we didn't know if the add
              ; succeeded or not, but we found them in the final set.
              recovered   (set/difference ok adds)]

          {:valid?          (and (empty? lost) (empty? unexpected) (empty? dups))
           :duplicates      dups
           :ok              (util/integer-interval-set-str ok)
           :lost            (util/integer-interval-set-str lost)
           :unexpected      (util/integer-interval-set-str unexpected)
           :recovered       (util/integer-interval-set-str recovered)
           :ok-frac         (util/fraction (count ok) (count attempts))
           :unexpected-frac (util/fraction (count unexpected) (count attempts))
           :lost-frac       (util/fraction (count lost) (count attempts))
           :recovered-frac  (util/fraction (count recovered) (count attempts))})))))

(defn ssh-set-client
  [conn]
  (reify client/Client
    (setup! [_ test node]
      (ssh-set-client (ssh-open node)))
      
    (invoke! [this test op]
      (util/timeout 5000 (assoc op :type :info, :error :timeout)
      (case (:f op)
        :add  (let [[res out err] (ssh-sql conn (str "begin "
                                                     "\"set transaction isolation level serializable\" "
                                                     "\"insert into jepsen.set values (" (:value op) ")\"  "
                                                     "commit"))]
                (if (zero? (:exit res))
                  (assoc op :type :ok)
                  (assoc op :type :fail, , :error err)))
        :read (let [[res out err] (ssh-sql conn (str "begin "
                                                     "\"set transaction isolation level serializable\" "
                                                     "\"select val from jepsen.set\"  "
                                                     "commit"))]
                (if (zero? (:exit res))
                  (assoc op :type :ok, :value (into [] (map str->int (drop-last 1 (drop 4 out)))))
                  (assoc op :type :fail, :error err)))
        )))
    
    (teardown! [_ test]
      (ssh/disconnect conn))
    ))

(defn with-nemesis
  "Wraps a client generator in a nemesis that induces failures and eventually
  stops."
  [client]
  (gen/phases
    (gen/phases
      (->> client
           (gen/nemesis
             (gen/seq (cycle [(gen/sleep 0)
                              {:type :info, :f :start}
                              (gen/sleep 5)
                              {:type :info, :f :stop}
                              ])))
           (gen/time-limit 30))
      (gen/nemesis (gen/once {:type :info, :f :stop}))
      (gen/sleep 5))))


(defn sets-test
  [nodes]
  (basic-test nodes
   {
    :name    "set"
    :client (ssh-set-client nil)
    :generator (gen/phases
                (->> (range)
                     (map (partial array-map
                                   :type :invoke
                                   :f :add
                                   :value))
                     gen/seq
                     (gen/stagger 1/10)
                     with-nemesis)
                (->> {:type :invoke, :f :read, :value nil}
                     gen/once
                     gen/clients))
    :checker (checker/compose
              {:perf (checker/perf)
               :set  (check-set-unique)})
    }
   ))


; -------------------------- Test for an increasing sequence

(defn check-order
  [prev rows res1 res2 ]
  (if (empty? rows) [res1 res2]
      (let [row (first rows)
            nres1 (concat res1 (if (> (nth prev 1) (nth row 1)) (list (list prev row)) ()))
            nres2 (concat res2 (if (>= (first prev) (first row)) (list (list prev row)) ()))]
        (check-order row (rest rows) nres1 nres2))))

(defn monotonic-order
  [rows]
  (check-order (first rows) (rest rows) () ()))

(defn check-monotonic
  "Given a set of :add operations followed by a final :read, verifies that
  every successfully added element is present in the read, and that the read
  contains only elements for which an add was succesful, and that all
  elements are unique and in the same order as database timestamps."
  []
  (reify checker/Checker
    (check [this test model history]
      (let [all-adds-l (->> history
                            (r/filter op/ok?)
                            (r/filter #(= :add (:f %)))
                            (r/map :value)
                            (into []))
            final-read-l (->> history
                              (r/filter op/ok?)
                              (r/filter #(= :read (:f %)))
                              (r/map :value)
                              (reduce (fn [_ x] x) nil)
                              )
            all-fails (->> history
                           (r/filter op/fail?))]
            (if-not final-read-l
              {:valid? false
               :error  "Set was never read"})
            
            (let [final-read  (into #{} final-read-l)
                  all-adds    (into #{} all-adds-l)
                  
                  retries    (->> all-fails
                                  (r/filter #(= :retry (:error %)))
                                  (into []))
                  aborts    (->> all-fails
                                 (r/filter #(= :abort-uncertain (:error %)))
                                 (into []))
                  [off-order-sts off-order-val] (monotonic-order final-read-l)

                  dups        (remove final-read final-read-l)
              
                  ; Lost records are those we definitely added but weren't read
                  lost        (into [] (set/difference all-adds (map first final-read)))]
          {:valid?          (and (empty? lost) (empty? dups) (empty? off-order-sts) (empty? off-order-val))
           ; :retries         retries
           :retry-frac      (util/fraction (count retries) (count history))
           ; :aborts          aborts
           :abort-frac      (util/fraction (count aborts) (count history))
           :lost            (into [] lost)
           :lost-frac       (util/fraction (count lost) (count all-adds-l))
           :duplicates      dups
           :order-by-errors off-order-sts
           :value-reorders  off-order-val
           })))))


(defn ssh-set-inc-client
  [conn nodenum]
  (reify client/Client
    (setup! [_ test node]
      (info "Setting up client for" (name node))
      (let [n (str->int (subs (name node) 1 2))]
        (info "Setting up client " n " for " (name node))
        (ssh-set-inc-client (ssh-open node) n)))
      
    (invoke! [this test op]
      (util/timeout 5000 (assoc op :type :info, :error :timeout)
      (case (:f op)
        :add  (let [[res out err] (ssh-sql conn (str "begin "
                                                     "\"set transaction isolation level serializable\" "
                                                     "\"select val,sts,node,tb from jepsen.mono0 where val = (select max(val) from jepsen.mono0)\" "
                                                     "\"insert into jepsen.mono0 (val, sts, node, tb) values ("
                                                     "1 + (select max(val) from jepsen.mono0),"
                                                     "extract(epoch_nanoseconds from now()), " nodenum ", 0)\" "
                                                     "commit"))]
                (assoc op :value nil)
                (if (zero? (:exit res))
                  (assoc op :type :ok, :value (map str->int (str/split (first (drop 4 out)) #"\s")), :info err)
                  (assoc op :type :fail, :error err)))
        :read (let [[res out err] (ssh-sql conn (str "begin "
                                                     "\"set transaction isolation level serializable\" "
                                                     "\"select val,sts,node,tb from jepsen.mono0 order by sts\"  "
                                                     "commit"))
                    rows (drop-last 1 (drop 4 out))]
                (if (zero? (:exit res))
                  (assoc op :type :ok, :value (doall (map (fn [r] (map str->int (str/split r #"\s"))) rows)))
                  (assoc op :type :fail, :error err)))
        )))
    
    (teardown! [_ test]
      (ssh/disconnect conn))
    ))


(defn monotonic-add-test
  [nodes]
  (basic-test nodes
   {
    :name    "monotonic-parts"
    :client (ssh-set-inc-client nil nil)
    :generator (gen/phases
                (->> (range)
                     (map (partial array-map
                                   :type :invoke
                                   :f :add
                                   :value))
                     gen/seq
                     (gen/stagger 1/10)
                     with-nemesis)
                (->> {:type :invoke, :f :read, :value nil}
                     gen/once
                     gen/clients))
    :checker (checker/compose
              {:perf (checker/perf)
               :set  (check-monotonic)})
    }
   ))

(defn monotonic-add-test-skews
  [nodes]
  (let [t (basic-test nodes
   {
    :name    "monotonic-skews"
    :client (ssh-set-inc-client nil nil)
    :generator (gen/phases
                (->> (range)
                     (map (partial array-map
                                   :type :invoke
                                   :f :add
                                   :value))
                     gen/seq
                     (gen/stagger 1/10)
                     with-nemesis)
                (->> {:type :invoke, :f :read, :value nil}
                     gen/once
                     gen/clients))
    :checker (checker/compose
              {:perf (checker/perf)
               :set  (check-monotonic)})
    }
   )]
    (assoc t :nemesis (clock-milli-scrambler 100))))

; --------------------------- Monotonic tests across multiple tables ---------------------

(defn check-monotonic-split
  "Same as check-monotonic, but check ordering only from each client's perspective."  
  []
  (reify checker/Checker
    (check [this test model history]
      (let [all-adds-l (->> history
                            (r/filter op/ok?)
                            (r/filter #(= :add (:f %)))
                            (r/map :value)
                            (into []))
            final-read-l (->> history
                              (r/filter op/ok?)
                              (r/filter #(= :read (:f %)))
                              (r/map :value)
                              (reduce (fn [_ x] x) nil)
                              )
            all-fails (->> history
                           (r/filter op/fail?))]
            (if-not final-read-l
              {:valid? false
               :error  "Set was never read"})
            
            (let [final-read  (into #{} final-read-l)
                  all-adds    (into #{} all-adds-l)
                  
                  retries    (->> all-fails
                                  (r/filter #(= :retry (:error %)))
                                  (into []))
                  aborts    (->> all-fails
                                 (r/filter #(= :abort-uncertain (:error %)))
                                 (into []))
                  sub-lists (->> (range 5)
                                 (map (fn [x] (->> final-read-l
                                                   (r/filter #(= x (nth % 2)))
                                                   (into ())
                                                   (reverse))))
                                 (into []))
                  off-order-pairs (map monotonic-order sub-lists)

                  off-order-sts (map first off-order-pairs)
                  off-order-val (map last off-order-pairs)

                  dups        (remove final-read final-read-l)
              
                  ; Lost records are those we definitely added but weren't read
                  lost        (into [] (set/difference all-adds (map first final-read)))]
          {:valid?          (and (empty? lost) (empty? dups) (and (map empty? off-order-sts)) (and (map empty? off-order-val)))
           ; :retries         retries
           :retry-frac      (util/fraction (count retries) (count history))
           ; :aborts          aborts
           :abort-frac      (util/fraction (count aborts) (count history))
           :lost            (into [] lost)
           :lost-frac       (util/fraction (count lost) (count all-adds-l))
           :duplicates      dups
           :order-by-errors off-order-sts
           :value-reorders  off-order-val
           })))))

(defn ssh-set-inc-spread-client
  [conn nodenum]
  (reify client/Client
    (setup! [_ test node]
      (info "Setting up client for" (name node))
      (let [n (str->int (subs (name node) 1 2))]
        (info "Setting up client " n " for " (name node))
        (ssh-set-inc-spread-client (ssh-open node) n)))
      
    (invoke! [this test op]
      (util/timeout 5000 (assoc op :type :info, :error :timeout)
      (case (:f op)
        :add  (let [rt (rand-int 5)
                    [res out err] (ssh-sql conn (str "begin "
                                                     "\"set transaction isolation level serializable\" "
                                                     "\"insert into jepsen.mono" rt " (val, sts, node, tb) "
                                                     "values (" (:value op) ","
                                                     "extract(epoch_nanoseconds from now()), " nodenum ", " rt ")\" "
                                                     "commit"))]
                (assoc op :value nil)
                (if (zero? (:exit res))
                  (assoc op :type :ok)
                  (assoc op :type :fail, :error err)))
        :read (let [triplets (into [] (map
                                       (fn [x]
                                         (let [[res out err] (ssh-sql conn (str "begin "
                                                                                "\"set transaction isolation level serializable\" "
                                                                                "\"select val,sts,node,tb from jepsen.mono" x " where tb <> -1\"  "
                                                                                "commit"))
                                               rows (drop-last 1 (drop 4 out))]
                                           [res rows err])) 
                                       (range 5)))]
                (if (zero? (->> triplets (map first) (map :exit) (reduce +)))
                  (assoc op :type :ok, :value (->> triplets
                                                   (map (fn [x] (nth x 1)))
                                                   (reduce (fn [x y] (concat x y)))
                                                   (map (fn [r] (map str->int (str/split r #"\s"))))
                                                   (sort-by (fn [x] (nth x 1)))
                                                   (into [])))
                  (assoc op :type :fail, :error (->> triplets
                                                     (map (fn [x] (nth x 2)))
                                                     (into [])))
                  ))
        )))
    
    (teardown! [_ test]
      (ssh/disconnect conn))
    ))


(defn monotonic-spread-add-test
  [nodes]
  (basic-test nodes
   {
    :name    "monotonic-spread-parts"
    :client (ssh-set-inc-spread-client nil nil)
    :generator (gen/phases
                (->> (range)
                     (map (partial array-map
                                   :type :invoke
                                   :f :add
                                   :value))
                     gen/seq
                     (gen/stagger 1/10)
                     with-nemesis)
                (->> {:type :invoke, :f :read, :value nil}
                     gen/once
                     gen/clients))
    :checker (checker/compose
              {:perf (checker/perf)
               :set  (check-monotonic-split)})
    }
   ))

(defn monotonic-spread-add-test-skews
  [nodes]
  (let [t (basic-test nodes
   {
    :name    "monotonic-spread-skews"
    :client (ssh-set-inc-spread-client nil nil)
    :generator (gen/phases
                (->> (range)
                     (map (partial array-map
                                   :type :invoke
                                   :f :add
                                   :value))
                     gen/seq
                     (gen/stagger 1/10)
                     with-nemesis)
                (->> {:type :invoke, :f :read, :value nil}
                     gen/once
                     gen/clients))
    :checker (checker/compose
              {:perf (checker/perf)
               :set  (check-monotonic-split)})
    }
   )]
    (assoc t :nemesis (clock-milli-scrambler 100))))


; --------------------------- Test for transfers between bank accounts -------------------

(defrecord SSHBankClient [conn n starting-balance]
  client/Client
    (setup! [this test node]
      (let [conn (ssh-open node)]
        (if (= node (jepsen/primary test))
                                        ; Create initial accts
          (dotimes [i n]
            (let [[res out err] (ssh-sql conn (str "\"insert into jepsen.accounts values (" i "," starting-balance ")\""))]
              (println "Created account " i " (" (:exit res) ", " err ")"))))
        (assoc this :conn conn))
      )

    (invoke! [this test op]
      (util/timeout 5000 (assoc op :type :info, :error :timeout)
               (case (:f op)
                 :read (let [[res out err] (ssh-sql conn (str "begin "
                                                              "\"set transaction isolation level serializable\" "
                                                              "\"select balance from jepsen.accounts\" "
                                                              "commit"))]
                         (if (zero? (:exit res))
                           (assoc op :type :ok, :value (map str->int (drop-last 1 (drop 4 out))))
                           (assoc op :type :fail, :error err)))

                 :transfer
                 (let [{:keys [from to amount]} (:value op)
                       [res out err] (ssh-sql conn (str "begin "
                                                        "\"set transaction isolation level serializable\" "
                                                        "\"select balance-" amount " from jepsen.accounts where id = " from "\" "
                                                        "\"select balance+" amount " from jepsen.accounts where id = " to "\" "
                                                        "\"update jepsen.accounts set balance=balance-" amount " where id = " from "\"  "
                                                        "\"update jepsen.accounts set balance=balance+" amount " where id = " to "\"  "
                                                        "commit"))]
                   (if (zero? (:exit res))
                     (assoc op :type :ok)
                     (assoc op :type :fail, :error err)))
                 )))

    (teardown! [_ test]
      (ssh/disconnect conn))
    )
  
(defn ssh-bank-client
  "Simulates bank account transfers between n accounts, each starting with
  starting-balance."
  [n starting-balance]
  (SSHBankClient. nil n starting-balance))

(defn bank-read
  "Reads the current state of all accounts without any synchronization."
  [_ _]
  {:type :invoke, :f :read})

(defn bank-transfer
  "Transfers a random amount between two randomly selected accounts."
  [test process]
  (let [n (-> test :client :n)]
    {:type  :invoke
    :f     :transfer
     :value {:from   (rand-int n)
             :to     (rand-int n)
             :amount (rand-int 5)}}))

(def bank-diff-transfer
  "Like transfer, but only transfers between *different* accounts."
  (gen/filter (fn [op] (not= (-> op :value :from)
                             (-> op :value :to)))
              bank-transfer))

(defn bank-checker
  "Balances must all be non-negative and sum to the model's total."
  []
  (reify checker/Checker
    (check [this test model history]
      (let [bad-reads (->> history
                           (r/filter op/ok?)
                           (r/filter #(= :read (:f %)))
                           (r/map (fn [op]
                                  (let [balances (:value op)]
                                    (cond (not= (:n model) (count balances))
                                          {:type :wrong-n
                                           :expected (:n model)
                                           :found    (count balances)
                                           :op       op}

                                         (not= (:total model)
                                               (reduce + balances))
                                         {:type :wrong-total
                                          :expected (:total model)
                                          :found    (reduce + balances)
                                          :op       op}))))
                           (r/filter identity)
                           (into []))]
        {:valid? (empty? bad-reads)
         :bad-reads bad-reads}))))

(defn bank-test
  [nodes n initial-balance]
  (basic-test nodes
    {:name "bank"
     ;:concurrency 20
     :model  {:n n :total (* n initial-balance)}
     :client (ssh-bank-client n initial-balance)
     :generator (gen/phases
                  (->> (gen/mix [bank-read bank-diff-transfer])
                       (gen/clients)
                       (gen/stagger 1/10)
                       (gen/nemesis
                        (gen/seq (cycle [(gen/sleep 5)
                                         {:type :info, :f :start}
                                         (gen/sleep 5)
                                         {:type :info, :f :stop}])))
                       (gen/time-limit 30))
                  (gen/log "waiting for quiescence")
                  (gen/sleep 30)
                  (gen/clients (gen/once bank-read)))
     :checker (checker/compose
                {:perf (checker/perf)
                 :bank (bank-checker)})}))
