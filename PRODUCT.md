# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Members of a nonprofit community makerspace, using the member portal from a laptop at home or a phone on the shop floor. Committee chairs and volunteers use the same portal to run their areas (shop equipment, classes, IT).

## Product Purpose

The member portal is the members' self-service home: profile, groups and committees, equipment status, remote access, reports, and an AI assistant. Success is a member getting a quick, trustworthy answer ("is my print done?", "who can help?") without asking a volunteer.

## Positioning

It is the one place that ties the space's own systems (directory, billing, door access, shop equipment) to the member's identity, so it can say "your print" and "your group" instead of showing anonymous data.

## Operating Context

- Server-rendered pages (Kotlin/Ktor + Thymeleaf + Bootstrap 5), single sign-on, feature flags for pilots.
- Committee pages surface area equipment; the 3D Fab committee's Bambu printer farm is reported through Bambuddy (state, job, progress, ETA, camera snapshot, who queued the print).
- Notifications (print-finished, printer-free, offline alerts) are opt-in per member.

## Capabilities and Constraints

- Pages render once per load; live updates are limited to explicit actions (e.g. an open camera view). No background polling of the page.
- Must work on phones as a primary size.
- Never show real member, host, or infrastructure data in code, tests, or mock content.

## Brand Commitments

Existing portal look: blue top navigation, Outfit headings, Inter body text, Material Symbols icons, Bootstrap components. New pages match the portal's real palette rather than introducing a separate identity.

## Evidence on Hand

Live data and camera snapshots come from the real printers; there are no marketing claims, testimonials, or metrics to show.

## Product Principles

- Answer the member's own question first ("my print", "my group"), then the general view.
- Prefer plain, short wording; details live in docs.
- Show real state honestly: unknown or unreachable is never shown as available.
