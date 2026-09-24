import type { Conversation, Group, User } from "../types/chat";
export const people: User[] = [
  {
    id: "indhuja",
    name: "Indhuja",
    online: true,
    designation: "Product designer",
  },
  {
    id: "ragul",
    name: "Ragul",
    online: true,
    designation: "Frontend developer",
  },
  {
    id: "alex",
    name: "Alex Morgan",
    online: false,
    designation: "Engineering",
  },
  {
    id: "priya",
    name: "Priya Ramasubramanian",
    online: false,
    designation: "Quality assurance",
  },
];
export const groups: Group[] = [
  {
    id: "development",
    name: "Development Team",
    memberIds: ["ragul", "alex", "indhuja"],
  },
  { id: "project", name: "Project Group", memberIds: ["ragul", "priya"] },
  {
    id: "announcements",
    name: "Company Announcements and Weekly Updates",
    memberIds: people.map((p) => p.id),
  },
];
export const mockConversations: Conversation[] = [
  {
    id: "DIRECT:indhuja",
    type: "DIRECT",
    targetId: "indhuja",
    name: "Indhuja",
    pinned: true,
    unread: 2,
    muted: false,
    hidden: false,
    updatedAt: "2026-09-09T09:40:00+05:30",
    messages: [
      {
        id: "m1",
        senderId: "indhuja",
        senderName: "Indhuja",
        text: "The new sidebar designs are ready. Let me know what you think!",
        sentAt: "2026-09-09T09:40:00+05:30",
      },
    ],
  },
  {
    id: "GROUP:development",
    type: "GROUP",
    targetId: "development",
    name: "Development Team",
    pinned: true,
    unread: 3,
    muted: false,
    hidden: false,
    updatedAt: "2026-09-09T09:35:00+05:30",
    messages: [
      {
        id: "m2",
        senderId: "alex",
        senderName: "Alex Morgan",
        text: "Good morning team! What are we working on today?",
        sentAt: "2026-09-09T09:30:00+05:30",
      },
      {
        id: "m3",
        senderId: "ragul",
        senderName: "Ragul",
        text: "I’m putting together the conversation sidebar.",
        sentAt: "2026-09-09T09:35:00+05:30",
      },
    ],
  },
  {
    id: "DIRECT:alex",
    type: "DIRECT",
    targetId: "alex",
    name: "Alex Morgan",
    pinned: false,
    unread: 1,
    muted: false,
    hidden: false,
    updatedAt: "2026-09-09T09:20:00+05:30",
    messages: [
      {
        id: "m4",
        senderId: "alex",
        senderName: "Alex Morgan",
        text: "Can we review the latest changes after lunch?",
        sentAt: "2026-09-09T09:20:00+05:30",
      },
    ],
  },
  {
    id: "GROUP:project",
    type: "GROUP",
    targetId: "project",
    name: "Project Group",
    pinned: false,
    unread: 0,
    muted: true,
    hidden: false,
    updatedAt: "2026-09-08T16:00:00+05:30",
    messages: [
      {
        id: "m5",
        senderId: "priya",
        senderName: "Priya Ramasubramanian",
        text: "The checklist includes all the scenarios for the next release, including mobile layout, search states, and long conversation previews.",
        sentAt: "2026-09-08T16:00:00+05:30",
      },
    ],
  },
];
