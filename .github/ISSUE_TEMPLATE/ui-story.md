---
name: UI story
about: One screen or flow in one UI (reader app or admin console)
title: "E?-? · "
labels: ["type:ui"]
---

<!-- Describe what the person sees and can do. One UI per issue: add ui:app or ui:admin, a size label and the epic's milestone. Do not name a framework or library. -->

**Story.** As a <reader or operator>, I want <what I see or do>, so that <benefit>.

**Acceptance criteria**

- [ ] 

**Out of scope.** 

**Depends on.** <the API story this screen needs>

---

**Definition of done (UI story)**

- [ ] It uses the API only through the client generated from the contract
- [ ] Loading, empty and error states are handled
- [ ] It works on every platform this UI targets (reader app: iOS, Android, web; admin console: web)
- [ ] Component tests cover the acceptance criteria
- [ ] Typecheck and lint pass
- [ ] It uses the UI's theme (reader app: Paper)
- [ ] No sample data remains on the screen
- [ ] CI is green
