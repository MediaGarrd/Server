workspace "MediaGarrd Server" "Automated backups for self-hosted services" {

    !identifiers hierarchical

    model {
        u = softwareSystem "MediaGarrd-Client" "MediaGarrd-Client service running on a separate machine" {
            tags "External"
        }

        docker = softwareSystem "Docker Engine" "Runs short-lived backup containers" {
            tags "External"
        }

        targets = softwareSystem "Self-hosted Services" "Services being backed up" {
            tags "External"
        }

        ss = softwareSystem "MediaGarrd" "Automated backups for self-hosted services" {
            server = container "Backup Server" "Serves API, schedules and orchestrates backups" "Java" {
                api = component "API" "Handles HTTP requests" "REST" {
                    tags "Api"
                }
                schedule = component "Scheduler" "Triggers scheduled runs" {
                    tags "Schedule"
                }
                bo = component "Orchestrator" "Coordinates runs, prunes stale backups" {
                    tags "Management"
                }
                ir = component "Runners" "Launch one backup container per service" {
                    tags "Runner"
                }
            }
            job = container "Backup Job" "Short-lived container that archives one service" "Docker image" {
                tags "Job"
            }
            db = container "Database" "Config and backup history" {
                tags "Database"
            }
            fs = container "Backup Storage" "Compressed backup archives" {
                tags "Filesystem"
            }
        }

        u -> ss.server.api "Manages config/backups via"
        ss.server.api -> ss.db "Reads config/history"
        ss.server.api -> ss.fs "Reads backup data"
        ss.server.api -> ss.server.bo "Triggers run"

        ss.server.schedule -> ss.server.bo "Triggers run"
        ss.server.bo -> ss.server.ir "Delegates per-service backups"
        ss.server.bo -> ss.fs "Deletes stale backups"

        ss.server.ir -> ss.db "Reads service config path, logs run results"
        ss.server.ir -> docker "Starts and monitors containers" "Docker API"
        docker -> ss.job "Runs"
        ss.job -> targets "Reads data from"
        ss.job -> ss.fs "Writes compressed archive"
    }

    views {
        systemContext ss "Context" {
            include *
            autolayout lr
        }

        container ss "Containers" {
            include *
            autolayout lr
        }

        component ss.server "Components" {
            include *
            autolayout lr
        }

        styles {
            element "Element" {
                color #0773af
                stroke #0773af
                strokeWidth 7
                shape roundedbox
            }
            element "Database" {
                shape cylinder
            }
            element "External" {
                stroke #999999
                color #999999
            }
            element "Boundary" {
                strokeWidth 5
            }
            relationship "Relationship" {
                thickness 4
            }
        }
    }

    configuration {
        scope softwaresystem
    }
}
