(ns keycloak.admin-test
  (:require
   [clojure.test :as t :refer [deftest testing is]]
   [testit.core :refer :all]
   [bean-dip.core :as bean-dip]
   [camel-snake-kebab.core :as csk :refer [->kebab-case]]
   [keycloak.admin :as admin]
   [keycloak.bean :as bean]
   [keycloak.user :as user]
   [keycloak.deployment :as deployment :refer [keycloak-client client-conf]])
  (:import [org.keycloak.representations.idm ClientRepresentation ProtocolMapperRepresentation]))


(def admin-login "admin")
(def admin-password "secretadmin")
(def auth-server-url "http://localhost:8090/auth")

(def integration-test-conf (deployment/client-conf "http://localhost:8090/auth" "master" "admin-cli"))
(def admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password))

; (def deployments (deployment-for-realms kc-admin-client auth-server-url client-account-backend ["electre"]))


(def LOGIN {:bruteForceProtected true
            :rememberMe true
            :resetPasswordAllowed true})

(def SMTP {:host "smtp.eu.mailgun.org"
           :port 587
           :from "admin@example.com"
           :auth true
           :starttls true
           :replyTo "example"
           :user "postmaster@mg.example.com"
           :password "yo"
           })

(def THEMES {:internationalizationEnabled true
             :supportedLocales #{"en" "fr"}
             :defaultLocale "fr"
             :loginTheme "mytheme"
             :accountTheme "mytheme"
             :adminTheme nil
             :emailTheme "mytheme"} )

(def TOKENS {:ssoSessionIdleTimeoutRememberMe (Integer. (* 60 60 48)) ;2 days
             :ssoSessionMaxLifespanRememberMe (Integer. (* 60 60 48))
             :ssoSessionMaxLifespan (Integer. (* 60 60 10)) ;10 hours
             })

(def REALM_REP_BEAN (into {} (map #(update % 0 csk/->kebab-case) (merge LOGIN SMTP THEMES TOKENS))))

(deftest ^:integration admin-test
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)]
    (testing "realm creation "
      (let [realm-name (str "keycloak-clojure-test-" (rand-int 1000))
            realm (admin/create-realm! admin-client {:name realm-name :themes THEMES :login LOGIN :smtp SMTP})]
        (is (= realm-name (.getRealm realm)))
        (testing "create a client, then a deployment for that client"
          (let [confid-client-id (str "keycloak-clojure-config-client-" (rand-int 1000))
                public-client-id (str "keycloak-clojure-public-client-" (rand-int 1000))
                public-client-id-2 (str "frontend-client-" (rand-int 1000))
                public-client-2 (admin/create-client! admin-client realm-name (admin/client public-client-id-2 true ["http://localhost:3000/*"] ["http://localhost:3000"]))
                confid-client (admin/create-client! admin-client realm-name confid-client-id false)
                public-client (admin/create-client! admin-client realm-name public-client-id true)]
            (is (= confid-client-id (.getClientId confid-client)))
            (is (= public-client-id (.getClientId public-client)))
            (is (= public-client-id-2 (.getClientId public-client-2)))))
        (testing "create a role in that realm"
          (let [role (admin/create-role! admin-client realm-name "employee")
                roles (admin/list-roles admin-client realm-name)]
            (is (> (count roles) 0))
            (is (not (nil? (admin/get-role admin-client realm-name "employee"))))))
        (testing (str "group creation in the realm" realm-name)
          (let [group-name (str "group-" (rand-int 1000))
                group (admin/create-group! admin-client realm-name group-name)]
            (is (= group-name (.getName group)))
            (testing "subgroup creation"
              (let [subgroup-name (str "subgroup-" (rand-int 1000))
                    subgroup (admin/create-subgroup! admin-client realm-name (.getId group) subgroup-name)]
                (is (= subgroup-name (.getName subgroup)))
                (testing "user creation in the realm then join to group"
                  (let [user-name (str "user-" (rand-int 1000))
                        user (admin/create-user! admin-client realm-name user-name "password")
                        joined-group (admin/add-user-to-group! admin-client realm-name (.getId subgroup) (.getId user))
                        members (admin/get-group-members admin-client realm-name (.getId subgroup))]
                    (is (= user-name (.getUsername user)))
                    (is (some #(= (.getId user) (.getId %)) members))
                    (user/delete-user! admin-client realm-name (.getId user))))))))
        (testing "realm deletion"
          (admin/delete-realm! admin-client realm-name)
          (is (thrown? javax.ws.rs.NotFoundException (admin/get-realm admin-client realm-name))))))))

(deftest ^:integration test-creation-user-with-client-roles
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)
        realm-name   (str "keycloak-clojure-test-" (rand-int 1000))
        realm-rep    (admin/create-realm! admin-client realm-name)]
    (testing "create a user with client roles"
      (let [user-name (str "user-" (rand-int 1000))
            user-rep      (user/create-or-update-user! admin-client "master"
                                                       {:username user-name :password "password"}
                                                       nil
                                                       {(str realm-name "-realm") ["impersonation"]})]
        (fact (.getUsername user-rep) => user-name)))
    (testing "realm deletion"
      (admin/delete-realm! admin-client realm-name)
      (is (thrown? javax.ws.rs.NotFoundException (admin/get-realm admin-client realm-name))))))

(defn keycloak-running? [keycloak-client]
  (try
    (-> keycloak-client (.realm "master") (.toRepresentation) bean)
    (catch javax.ws.rs.ProcessingException pe false)
    (catch java.net.ConnectException ce false)))

(deftest test-realm-representation []
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)
        _            (assert (keycloak-running? admin-client))
        name   (str "keycloak-clojure-test-" (rand-int 1000))
        realm-rep    (admin/realm-representation-from-map {:name                  name
                                                           :themes                THEMES 
                                                           :login                 LOGIN
                                                           :tokens                TOKENS
                                                           :smtp                  SMTP})]
    (fact (bean/RealmRepresentation->map realm-rep) =in=> {:realm                                name
                                                           :sso-session-max-lifespan             (Integer. (* 60 60 10))
                                                           :sso-session-idle-timeout-remember-me (Integer. (* 60 60 48))
                                                           :sso-session-max-lifespan-remember-me (Integer. (* 60 60 48))
                                                           :remember-me?                         true
                                                           :enabled?                             true
                                                           :login-theme                          "mytheme"
                                                           :account-theme                        "mytheme"
                                                           :brute-force-protected?               true
                                                           :default-locale                       "fr"})))

(deftest ^:integration test-create-realm
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)]
    (assert (keycloak-running? admin-client))
    (testing "realm creation "
      (let [name      (str "keycloak-clojure-test-" (rand-int 1000))
            realm-rep (admin/create-realm! admin-client {:name name :smtp SMTP :login LOGIN :tokens TOKENS :themes THEMES})]
        (facts (.getId realm-rep) => name
               (bean/RealmRepresentation->map realm-rep) =in=> {:realm                                name
                                                                :sso-session-max-lifespan             (Integer. (* 60 60 10))
                                                                :sso-session-idle-timeout-remember-me (Integer. (* 60 60 48))
                                                                :sso-session-max-lifespan-remember-me (Integer. (* 60 60 48))
                                                                :remember-me?                         true
                                                                :enabled?                             true
                                                                :login-theme                          "mytheme"
                                                                :account-theme                        "mytheme"
                                                                :brute-force-protected?               true
                                                                :default-locale                       "fr"})
        (testing "realm deletion"
          (admin/delete-realm! admin-client name)
          (is (thrown? javax.ws.rs.NotFoundException (admin/get-realm admin-client name))))))))

(deftest ^:integration test-update-client
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)
        _            (assert (keycloak-running? admin-client))
        realm-name   (str "keycloak-clojure-test-" (rand-int 1000))
        realm-rep    (admin/create-realm! admin-client realm-name)]
    (testing "create a client then update it"
      (let [client-id (str "client-" (rand-int 1000))
            client    (admin/create-client! admin-client realm-name (admin/client {:client-id client-id :name client-id :public? false}))]
        (is client)
        (fact (->> {:client-id client-id :name "new-name" :public? true}
                   admin/client
                   (admin/update-client! admin-client realm-name)
                   (bean/ClientRepresentation->map)) =in=> {:client-id client-id :name "new-name" :public-client? true})
        ;;updating an existing client should not throw an error (for the moment)
        (fact (some->> {:client-id "new-id" :name "new-name" :public? true}
                       admin/client
                       (admin/update-client! admin-client realm-name)
                       (bean/ClientRepresentation->map)) => nil)
        (fact (->> {:client-id "new-id" :name "new-name" :public? true}
                   admin/client
                   (admin/create-or-update-client! admin-client realm-name)
                   (bean/ClientRepresentation->map)) =in=> {:client-id "new-id" :name "new-name" :public-client? true})))
    (testing "realm deletion"
      (admin/delete-realm! admin-client realm-name)
      (is (thrown? javax.ws.rs.NotFoundException (admin/get-realm admin-client realm-name))))))


(deftest ^:integration test-create-or-update-client
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)
        _            (assert (keycloak-running? admin-client))
        realm-name   (str "keycloak-clojure-test-" (rand-int 1000))
        realm        (admin/create-realm! admin-client realm-name)]
    (testing "create a client then update it"
      (let [client-id (str "client-" (rand-int 1000))
            client    (->> {:client-id client-id :name client-id :public? false}
                           admin/client
                           (admin/create-or-update-client! admin-client realm-name)
                           (bean/ClientRepresentation->map))]
        (fact client =in=> {:client-id client-id :name client-id :public-client? false})))
    (testing "realm deletion"
      (admin/delete-realm! admin-client realm-name)
      (is (thrown? javax.ws.rs.NotFoundException (admin/get-realm admin-client realm-name))))))


(deftest ^:integration test-create-or-update-client-access-token-lifespan
  (let [admin-client (deployment/keycloak-client integration-test-conf admin-login admin-password)
        _            (assert (keycloak-running? admin-client))
        realm-name   (str "keycloak-clojure-test-" (rand-int 1000))
        realm        (admin/create-realm! admin-client realm-name)]
    (testing "create a client then update it"
      (let [client-id (str "client-" (rand-int 1000))
            client    (->> {:client-id client-id :name client-id :public? false :attributes {"access.token.lifespan" "300"}}
                           admin/client
                           (admin/create-or-update-client! admin-client realm-name)
                           (bean/ClientRepresentation->map))]
        (fact client =in=> {:client-id client-id :name client-id :public-client? false})
        (fact (:attributes client) => {"access.token.lifespan" "300"})))
    (testing "realm deletion"
      (admin/delete-realm! admin-client realm-name)
      (is (thrown? javax.ws.rs.NotFoundException (admin/get-realm admin-client realm-name))))))
