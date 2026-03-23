# spinx

Spinx is a multi-cloud deployment CLI for unified container deployment across AWS Fargate, GCP Cloud Run, Azure Container Apps, and VPS (via Kamal).

## Build & Test

```bash
./gradlew build          # build the project
./gradlew test           # run tests
./gradlew shadowJar      # build fat JAR
```

## gstack

Use /browse from gstack for all web browsing. Never use mcp__claude-in-chrome__* tools.

Available skills: /office-hours, /plan-ceo-review, /plan-eng-review, /plan-design-review, /design-consultation, /review, /ship, /land-and-deploy, /canary, /benchmark, /browse, /qa, /qa-only, /design-review, /setup-browser-cookies, /setup-deploy, /retro, /investigate, /document-release, /codex, /cso, /autoplan, /careful, /freeze, /guard, /unfreeze, /gstack-upgrade.

If gstack skills aren't working, run `cd .claude/skills/gstack && ./setup` to build the binary and register skills.

## BMad Method

BMad is installed for AI-driven agile development workflows.

Available agents and skills: /bmad-help (start here), /bmad-pm, /bmad-analyst, /bmad-architect, /bmad-dev, /bmad-qa, /bmad-sm, /bmad-tech-writer, /bmad-ux-designer, /bmad-create-prd, /bmad-create-architecture, /bmad-sprint-planning, /bmad-sprint-status, /bmad-create-epics-and-stories, /bmad-review-adversarial-general, /bmad-code-review, /bmad-retrospective, and more.

BMad configuration is in `_bmad/`. Output artifacts go to `_bmad-output/`.
